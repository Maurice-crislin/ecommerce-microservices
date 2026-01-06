package org.example.orderservice.service.impl;


import lombok.RequiredArgsConstructor;
import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.common.payment.dto.PaymentRequest;
import org.common.payment.enums.PaymentStatus;
import org.common.payment.dto.PaymentResponse;
import org.common.product.dto.BatchProductPriceRequest;
import org.common.product.dto.BatchProductPriceResponse;
import org.common.product.dto.ProductPriceResponse;
import org.example.orderservice.OrderRepository.OrderRepository;
import org.example.orderservice.client.InventoryClient;
import org.example.orderservice.client.PaymentClient;
import org.example.orderservice.client.ProductClient;
import org.example.orderservice.dto.*;
import org.example.orderservice.entity.Order;
import org.common.order.enums.OrderStatus;
import org.example.orderservice.entity.OrderItem;
import org.example.orderservice.messaging.OrderMessageProducer;
import org.example.orderservice.service.OrderService;
import org.example.orderservice.utils.IdGenerator;
import org.springframework.stereotype.Service;


import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final ProductClient productClient;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;

    private final IdGenerator idGenerator;
    private final OrderRepository orderRepository;
    private final OrderMessageProducer orderMessageProducer;

    @Override
    public void createOrder(OrderRequest request){

        List<org.common.inventory.dto.StockRequest> stockRequestList  = request.getProductRequests();
        Long orderId = idGenerator.generateOrderId();
        String userId = request.getUserId();

        // 1.batch check price and valid
        List<Long> productCodes = request.getProductRequests().stream().map(StockRequest::getProductCode).toList();
        BatchProductPriceResponse batchProductPriceResponse = productClient.getBatchPrice( new BatchProductPriceRequest(productCodes));

        if (!batchProductPriceResponse.isAllProductsOrderable()) {
            throw new IllegalStateException("Some products are not orderable");
        }

        // 2. lock inventory and check lock result

        InventoryBatchRequest batchLockRequest = new InventoryBatchRequest(orderId,stockRequestList);
        SimpleResponse lockResponse = inventoryClient.batchLockInventory(batchLockRequest);

        if (!lockResponse.isSuccess()) {
            throw new IllegalStateException(lockResponse.getMessage());
        }

        // 3. make price map

        List<ProductPriceResponse> products = batchProductPriceResponse.getProducts();

        Map<Long,BigDecimal> priceMap = products.stream()
                .filter(p -> p.getPrice() != null)
                .collect(Collectors.toMap(ProductPriceResponse::getProductCode, ProductPriceResponse::getPrice));

        // 4. create order and orderitems
        Order order = new Order();
        order.setOrderId(orderId);
        order.setUserId(userId);
        order.setOrderStatus(OrderStatus.PROCESSING);


        List<OrderItem> orderItems = stockRequestList.stream().map(stockRequest -> {
            BigDecimal unitPrice = priceMap.get(stockRequest.getProductCode());
            if (unitPrice == null) {
                throw new IllegalStateException("Price not found for product: " + stockRequest.getProductCode());
            }
            OrderItem item = new OrderItem();
            item.setProductCode(stockRequest.getProductCode());
            item.setQuantity(stockRequest.getQuantity());
            item.setUnitPrice(unitPrice);
            return item;
        }).toList();

        order.setOrderItems(orderItems);

        // 5.calculate price
        BigDecimal totalPrice = stockRequestList.stream()
                .map(stockRequest -> {
                    BigDecimal unitPrice = priceMap.get(stockRequest.getProductCode());
                    // if(unitPrice == null) return BigDecimal.ZERO;
                    return unitPrice.multiply(BigDecimal.valueOf(stockRequest.getQuantity()));
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        order.setTotalAmount(totalPrice);

        // 6. save order
        orderRepository.save(order);


        // 7. send 'OrderTimeout' delay message
        orderMessageProducer.sendOrderTimeoutDelayMessage(orderId);

    }

    @Override
    public  void payOrder(Long orderId){
        Order order = orderRepository.findOrderByOrderId(orderId).orElseThrow(() -> new IllegalStateException("order not found " + orderId));

        String userId = order.getUserId();
        BigDecimal totalAmount = order.getTotalAmount();
        PaymentRequest paymentRequest = new PaymentRequest(orderId,userId,totalAmount);
        PaymentResponse  paymentResponse = paymentClient.payment(paymentRequest);

        if( paymentResponse.getStatus() !=  PaymentStatus.PROCESSING ){
            throw new IllegalStateException("cannot payment" + paymentResponse.getMessage());
        }

    }
}
