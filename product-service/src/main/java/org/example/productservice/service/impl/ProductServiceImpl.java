package org.example.productservice.service.impl;


import lombok.RequiredArgsConstructor;
import org.example.productservice.dto.*;
import org.example.productservice.entity.Product;
import org.example.productservice.enums.ProductStatus;
import org.example.productservice.repository.ProductRepository;
import org.example.productservice.service.ProductService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.lang.Thread.sleep;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {
    private final ProductRepository productRepository;
    private final RedisTemplate<String,Object> redisTemplate;

    private static final String PRODUCT_DETAIL_PREFIX = "product:detail:";
    private static final String PRODUCT_LOCK_PREFIX = "product:lock:";

    @Value("${spring.data.redis.timeout}")
    private int timeout;

    public void setRedisKV(String key, Object value, long timeout, TimeUnit unit) {
        try{
            redisTemplate.opsForValue().set(key, value, timeout, unit);
        } catch(Exception e){
            e.printStackTrace();
        }
    }



    @Override
    public ProductPriceResponse getProductPrice(Long productCode){
        if(productCode == null){
            throw new IllegalArgumentException("Product code is null");
        }
        String cacheKey = PRODUCT_DETAIL_PREFIX + productCode;
        String lockKey = PRODUCT_LOCK_PREFIX + productCode;

        // 1.查redis
        try {
            Product product = (Product) redisTemplate.opsForValue().get(cacheKey);

            if (product != null) {
                // product自身不为空, 且不是占位空白对象, 即为有效对象,返回查询结果
                if(product.getProductCode() != null) return  mapToProductPriceResponse(product);
                // 非空的空白占位对象,显示不存在
                throw new IllegalArgumentException("Product not found: " + productCode);
            }

        } catch (Exception e) {
            // redis查询出错,降级db查询
            Optional<Product> optionalProduct = productRepository
                    .findProductByProductCode(productCode);

            if(optionalProduct.isEmpty()){
                // 写redis空对象 防穿透 默认过期时间较短
                this.setRedisKV(cacheKey, new Product(),5, TimeUnit.MINUTES);


                throw new IllegalArgumentException("Product not found: " + productCode);
            }

            Product product = optionalProduct.get();

            // 尝试回写redis
            this.setRedisKV(cacheKey, product,timeout, TimeUnit.MILLISECONDS);

            return mapToProductPriceResponse(product);
        }


        // true 代表之前不存在并且当下被设置
        // 锁过期时间应明显长于预估的最大 DB 查询耗时（如 10~30 秒
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey,"1",20,TimeUnit.SECONDS);

        if(Boolean.TRUE.equals(locked)){
            // 获得锁
            try {
                // 1.Double-Check Missing 重复查一次redis,也许在等待锁的过程中,redis被写入值了
                Product product = (Product) redisTemplate.opsForValue().get(cacheKey);

                if (product != null) {
                    // product自身不为空, 且不是占位空白对象, 即为有效对象,返回查询结果
                    if(product.getProductCode() != null) return  mapToProductPriceResponse(product);
                    // 非空的空白占位对象,显示不存在
                    throw new IllegalArgumentException("Product not found: " + productCode);
                }
                // 2.查db
                Optional<Product> optionalProduct = productRepository
                        .findProductByProductCode(productCode);

                if(optionalProduct.isEmpty()){
                    // 写redis空对象 防穿透 默认过期时间较短
                    this.setRedisKV(cacheKey, new Product(),5, TimeUnit.MINUTES);

                    throw new IllegalArgumentException("Product not found: " + productCode);
                }

                product = optionalProduct.get();


                // 3.写redis
                this.setRedisKV(cacheKey, product,timeout, TimeUnit.MILLISECONDS);



                return mapToProductPriceResponse(product);
            } finally {
                redisTemplate.delete(lockKey);
            }

        } else {
            try{
                Thread.sleep(50);
            } catch(InterruptedException ignored){}

            // 休眠重试
            return getProductPrice(productCode);
        }


    }

    /**
     * 被 getBatchProductPrices 调用,属于基础方法, 只负责批量查询数据并转换为 DTO
     * 不对接任何外部调用端点
     * @param productCodes
     * @return
     */
    @Override
    public  List<ProductPriceResponse> getProductPrices(List<Long> productCodes){
        List<Product> products = productRepository
                .findProductsByProductCodeIn(productCodes);

        return products.stream()
                .map(this::mapToProductPriceResponse)
                .collect(Collectors.toList());
    }

    /**
     * 业务增强方法，在查询基础(getProductPrices)上增加了：按请求顺序重排、识别缺失产品、计算可下单状态
     * @param productCodes
     * @return
     */
    @Override
    public BatchProductPriceResponse getBatchProductPrices(List<Long> productCodes){
        List<ProductPriceResponse> products = getProductPrices(productCodes);


        Map<Long, ProductPriceResponse> productMap = products.stream()
                .collect(Collectors.toMap(ProductPriceResponse::getProductCode,
                        productPriceResponse -> productPriceResponse)
                );
        // reorder by productCodes
        List<ProductPriceResponse> orderedProducts = productCodes.stream().map(productMap::get).filter(Objects::nonNull).toList();


        List<Long> missingProductCodes = productCodes.stream().filter(code -> !productMap.containsKey(code)).toList();

        boolean allProductsOrderable = missingProductCodes.isEmpty() && orderedProducts.stream().allMatch(p -> p != null && p.getStatus() == ProductStatus.ACTIVE);


        return new BatchProductPriceResponse(allProductsOrderable, orderedProducts, missingProductCodes);
    }
    private  ProductPriceResponse mapToProductPriceResponse(Product product){
        return new ProductPriceResponse(
                product.getProductCode(),
                product.getPrice(),
                product.getStatus()
        );
    }
    @Override
    public void deleteProduct(Long productCode) {
        String cacheKey = PRODUCT_DETAIL_PREFIX + productCode;

        productRepository.deleteById(productCode);

        // 第一次删除缓存
        redisTemplate.delete(cacheKey);
        try{
            sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 保持中断状态
            // 日志记录，但不影响继续执行第二次删除
        }
        // 第二次删除缓存（确保清空可能被并发读线程写入的旧数据）
        redisTemplate.delete(cacheKey);
    }

    @Override
    public ProductResponse addProduct(ProductCreateRequest productCreateRequest){
        Product product = new Product();
        product.setProductName(productCreateRequest.getProductName());
        product.setPrice(productCreateRequest.getPrice());
        Product savedProduct = productRepository.save(product);

        return  mapToProductResponse(savedProduct);
    }

    /**
     * 先查 → 再改 → 再存 → 再转 DTO
     */
    @Override
    public ProductResponse updateProduct(Long productCode, ProductUpdateRequest productUpdateRequest){
        String cacheKey = PRODUCT_DETAIL_PREFIX + productCode;

        Product product = productRepository.findProductByProductCode(productCode).orElseThrow(() -> new IllegalArgumentException("Product not found: " + productCode));

        if(productUpdateRequest.getPrice() != null){
            product.setPrice(productUpdateRequest.getPrice());
        }
        Product savedProduct = productRepository.save(product);

        // 第一次删除缓存
        redisTemplate.delete(cacheKey);
        try{
            sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 保持中断状态
            // 日志记录，但不影响继续执行第二次删除
        }
        // 第二次删除缓存（确保清空可能被并发读线程写入的旧数据）
        redisTemplate.delete(cacheKey);

        return  mapToProductResponse(savedProduct);
    }

    private ProductResponse mapToProductResponse(Product product){
        return new ProductResponse(
                product.getProductCode(),
                product.getProductName(),
                product.getPrice(),
                product.getStatus()
        );
    }

}
