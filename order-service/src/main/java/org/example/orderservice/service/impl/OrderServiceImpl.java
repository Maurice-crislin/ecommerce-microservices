package org.example.orderservice.service.impl;


import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.common.order.enums.OrderIdeStatus;
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
import org.example.orderservice.exception.RetryLaterException;
import org.example.orderservice.messaging.OrderMessageProducer;
import org.example.orderservice.service.OrderBaseService;
import org.example.orderservice.service.OrderService;
import org.example.orderservice.utils.IdGenerator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.reactive.function.client.WebClientResponseException;


import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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

    private final RedisTemplate<String, IdempotencyRecord> redisTemplate;

    public static String PREFIX_CREATE_ORDER = "order:create:";
    private final OrderService orderService;
    private final OrderBaseService orderBaseService;


    private void setKV(String key, Long orderId, OrderIdeStatus status, Duration duration) {
        IdempotencyRecord ideRecord = new IdempotencyRecord(orderId, status);
        this.redisTemplate.opsForValue().set(key, ideRecord, duration);
    }


    @Override
    @Transactional
    public void createOrder(OrderRequest request) {
        String idempotencyKey = request.getIdempotencyKey();

        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }

        String REDIS_IDE_KEY = PREFIX_CREATE_ORDER + idempotencyKey;
        Long orderId = idGenerator.generateOrderId();
        IdempotencyRecord ideRecord = new IdempotencyRecord(orderId, OrderIdeStatus.PROCESSING);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(REDIS_IDE_KEY, ideRecord, Duration.ofMinutes(5));

        if (Boolean.FALSE.equals(acquired)) {
            // 重复请求
            ideRecord = redisTemplate.opsForValue().get(REDIS_IDE_KEY);

            switch (ideRecord.getStatus()) {
                case FAILED_FINAL:
                    throw new IllegalStateException("failed final");
                case SUCCESS:
                    return; // 幂等成功
                case FAILED_RETRY:
                    // 从幂等记录获得固定的orderid,继续真实执行逻辑去retry
                    // 上一次失败,记录回滚,所以db里面没有对应order的
                    orderId = ideRecord.getOrderId();
                    break;
                case PROCESSING:
                    orderId = ideRecord.getOrderId();
                    // 如果redis没有,但是db有,以db作为权威
                    if (orderRepository.existsByIdempotencyKey(idempotencyKey)) {
                        this.setKV(REDIS_IDE_KEY, orderId, OrderIdeStatus.SUCCESS, Duration.ofHours(24));
                        return; // 幂等成功
                    } else {
                        // db也没有,代表有成功线程在工作中,当前请求请等待重试
                        this.setKV(REDIS_IDE_KEY, orderId, OrderIdeStatus.FAILED_RETRY, Duration.ofMinutes(30));
                        throw new RetryLaterException("Request is being processed, please retry later");
                    }
            }

        }

        final Long currentOrderId = orderId;
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        IdempotencyRecord currentRecord = redisTemplate.opsForValue().get(REDIS_IDE_KEY);
                        if (currentRecord != null && currentRecord.getStatus() == OrderIdeStatus.SUCCESS) {
                            return;
                        }
                        setKV(REDIS_IDE_KEY, currentOrderId, OrderIdeStatus.SUCCESS, Duration.ofHours(24));
                        orderMessageProducer.sendOrderTimeoutDelayMessage(currentOrderId);
                    }

                    @Override
                    public void afterCompletion(int status) {
                        if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                            IdempotencyRecord idempotencyRecord = redisTemplate.opsForValue().get(REDIS_IDE_KEY);
                            if (idempotencyRecord != null && idempotencyRecord.getStatus() == OrderIdeStatus.PROCESSING) {
                                setKV(REDIS_IDE_KEY, currentOrderId, OrderIdeStatus.FAILED_RETRY, Duration.ofMinutes(30));
                            }
                        }
                    }
                }
        );

        List<StockRequest> stockRequestList = request.getProductRequests();
        String userId = request.getUserId();
        Order order = new Order(currentOrderId, userId);
        order.setIdempotencyKey(idempotencyKey);
        order.setOrderStatus(OrderStatus.AWAITING_PAYMENT);
        order.setTotalAmount(BigDecimal.ZERO);

        // 0. 保存占位订单一定要早,看会不会触发唯一约束错误
        try {
            order = orderRepository.saveAndFlush(order);
        } catch (DuplicateKeyException e) {
            // 幂等键已存在, 说明其他请求已创建订单, 当前事务回滚。
            // Redis 中已有 (currentOrderId, PROCESSING) 记录, 直接置为 SUCCESS 完成幂等,
            // 无需查 DB (createOrder 返回空 body, 客户端不依赖此 orderId)。
            this.setKV(REDIS_IDE_KEY, currentOrderId, OrderIdeStatus.SUCCESS, Duration.ofHours(24));
            return;
        }

        // 1. 批量查价 + 校验: 所有产品是否可下单
        List<Long> productCodes = stockRequestList.stream().map(StockRequest::getProductCode).toList();
        BatchProductPriceResponse priceResponse = productClient.getBatchPrice(new BatchProductPriceRequest(productCodes));

        if (!priceResponse.isAllProductsOrderable()) {
            this.setKV(REDIS_IDE_KEY, currentOrderId, OrderIdeStatus.FAILED_FINAL, Duration.ofHours(24));
            throw new IllegalStateException("Some products are not orderable");
        }

        // 2. 批量预占库存
        InventoryBatchRequest batchLockRequest = new InventoryBatchRequest(currentOrderId, stockRequestList);
        invokeLockInventory(batchLockRequest, currentOrderId, REDIS_IDE_KEY);

        // 3. 构建订单明细 + 计算总价 (只依赖查价结果, 不依赖锁库存, 但因业务校验前置放锁库之后)
        Order finalOrder = buildOrderFromPriceResponse(order, priceResponse, stockRequestList);
        orderRepository.save(finalOrder);
    }

    // ==================== 私有方法 ====================

    private void invokeLockInventory(InventoryBatchRequest lockRequest, Long orderId, String redisIdeKey) {
        try {
            // 200 409 400 500
            // 只看状态码 返回没有意义
            inventoryClient.batchLockInventory(lockRequest);
        } catch (WebClientResponseException e) {
            int statusCode = e.getStatusCode().value();

            if (statusCode == 409 ) {
                // 409: 并发冲突 / 处理中 -> 标记可重试
                setKV(redisIdeKey, orderId, OrderIdeStatus.FAILED_RETRY, Duration.ofMinutes(30));
                throw new RetryLaterException("Inventory service temporary error(409)" + e.getMessage());
            } if (statusCode == 500) {
                // 500: 服务端代码崩溃 / 数据库断开 -> 标记可重试
                setKV(redisIdeKey, orderId, OrderIdeStatus.FAILED_RETRY, Duration.ofMinutes(30));
                throw new RetryLaterException("Inventory server error (500), will retry later.");
            } if (e.getStatusCode().is4xxClientError()) {
                // 400 或其他 4xx: 参数错误 / 状态不合法 -> 终态失败，绝不重试
                setKV(redisIdeKey, orderId, OrderIdeStatus.FAILED_FINAL, Duration.ofHours(24));
                throw new IllegalStateException("Inventory client error(4xx): " + e.getMessage());
            } else {
                // 其他未知的 HTTP 状态码，保守起见视作终态失败
                setKV(redisIdeKey, orderId, OrderIdeStatus.FAILED_FINAL, Duration.ofHours(24));
                throw new IllegalStateException("Unexpected HTTP status: " + statusCode);
            }
        } catch (Exception e) {
            // 网络超时（Timeout）、连接拒绝（ConnectException）、服务彻底宕机等
            // 属于基础设施层面的临时不可用 -> 标记可重试
            setKV(redisIdeKey, orderId, OrderIdeStatus.FAILED_RETRY, Duration.ofMinutes(30));
            throw new RetryLaterException("Inventory service unavailable" + e.getMessage());
        }
    }

    private Order buildOrderFromPriceResponse(Order order, BatchProductPriceResponse priceResponse,
                                               List<StockRequest> stockRequests) {
        Map<Long, BigDecimal> priceMap = buildPriceMap(priceResponse.getProducts());
        List<OrderItem> orderItems = buildOrderItems(stockRequests, priceMap);
        BigDecimal totalPrice = calculateTotal(stockRequests, priceMap);

        order.setOrderItems(orderItems);
        order.setTotalAmount(totalPrice);
        return order;
    }

    private Map<Long, BigDecimal> buildPriceMap(List<ProductPriceResponse> products) {
        return products.stream()
                .filter(p -> p.getPrice() != null)
                .collect(Collectors.toMap(ProductPriceResponse::getProductCode, ProductPriceResponse::getPrice));
    }

    private List<OrderItem> buildOrderItems(List<StockRequest> stockRequests, Map<Long, BigDecimal> priceMap) {
        return stockRequests.stream().map(stockRequest -> {
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
    }

    private BigDecimal calculateTotal(List<StockRequest> stockRequests, Map<Long, BigDecimal> priceMap) {
        return stockRequests.stream()
                .map(stockRequest -> {
                    BigDecimal unitPrice = priceMap.get(stockRequest.getProductCode());
                    return unitPrice.multiply(BigDecimal.valueOf(stockRequest.getQuantity()));
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }


    @Override
    public void payOrder(Long orderId) {
        Order order =  orderRepository.findOrderByOrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("order not found " + orderId));

        if (order.getOrderStatus() == OrderStatus.PAID) {
            // 支付成功,友好幂等
            return;
        }
        if (order.getOrderStatus() == OrderStatus.FAILED ||  order.getOrderStatus() == OrderStatus.CANCELED || order.getOrderStatus() == OrderStatus.TIMEOUT) {
            // 友好幂等 错误由外部controller捕获
            throw new IllegalStateException("order can not pay now");
        }


        try{
            order = orderBaseService.markPaying(orderId);
        } catch(IllegalStateException e){
            // 可能是并发导致状态已变，重新查询
            Order finalOrder = orderRepository.findOrderByOrderId(orderId).orElseThrow(() -> new IllegalArgumentException("order not found " + orderId));
            if (finalOrder.getOrderStatus() == OrderStatus.PAID) {
                return; // 已支付成功，友好返回
            }
            if (finalOrder.getOrderStatus() == OrderStatus.PAYING) {
                // 前一个请求已成功 markPaying，但尚未完成 finalize（paymentClient 调用或 finalizeOrderAfterPayment 可能因网络等问题延迟）
                // 引导客户端重试，由后端的补偿机制兜底
                throw new RetryLaterException("Payment is being processed, please retry later");
            }
            throw e; // 其他情况（如状态为 FAILED/PAYING以外）继续抛异常
        }

        String userId = order.getUserId();
        BigDecimal totalAmount = order.getTotalAmount();
        PaymentRequest paymentRequest = new PaymentRequest(orderId, userId, totalAmount);

        PaymentResponse paymentResponse;
        try {
            // Payment Service 现在同步返回最终结果（PAID / FAILED）
            paymentResponse = paymentClient.payment(paymentRequest);
        } catch (Exception e) {
            throw new RetryLaterException("Payment service error: " + e.getMessage());
        }


        final PaymentStatus finalStatus = paymentResponse.getStatus();
        orderBaseService.finalizeOrderAfterPayment(finalStatus, orderId);
    }
}