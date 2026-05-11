package org.example.productservice.service.impl;


import lombok.RequiredArgsConstructor;
import org.example.productservice.dto.*;
import org.example.productservice.entity.Product;
import org.example.productservice.enums.ProductStatus;
import org.example.productservice.repository.ProductRepository;
import org.example.productservice.service.ProductService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
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
    private static final int LOCK_RETRY_MAX_ATTEMPTS = 5;
    private static final long LOCK_RETRY_SLEEP_MS = 50;
    private static final long CACHE_DELETE_SLEEP_MS = 100;

    @Value("${product.cache.ttl:1800000}")
    // 真正商品缓存时间
    private int cacheTtl;

    public void setRedisKV(String key, Object value, long timeout, TimeUnit unit) {
        try{
            redisTemplate.opsForValue().set(key, value, timeout, unit);
        } catch(Exception e){
            e.printStackTrace();
        }
    }



    @Override
    public ProductPriceResponse getProductPrice(Long productCode){
        return getProductPriceWithRetry(productCode, 0);
    }

    /**
     * 带重试上限的缓存查询方法,避免递归溢出
     */
    private ProductPriceResponse getProductPriceWithRetry(Long productCode, int attempt){
        if(productCode == null){
            throw new IllegalArgumentException("Product code is null");
        }
        if(attempt >= LOCK_RETRY_MAX_ATTEMPTS){
            throw new RuntimeException("Failed to acquire lock for product: " + productCode + " after " + LOCK_RETRY_MAX_ATTEMPTS + " attempts");
        }

        String cacheKey = PRODUCT_DETAIL_PREFIX + productCode;
        String lockKey = PRODUCT_LOCK_PREFIX + productCode;

        // 1.查redis
        Product product = null;
        try {
            product = (Product) redisTemplate.opsForValue().get(cacheKey);
        } catch (Exception e) {
            // redis查询出错, product保持null, 后续走锁+DB查询路径
            e.printStackTrace();
        }

        // 空值/占位对象检测在try块之外,抛出的异常不会被catch拦截
        if (product != null) {
            // product自身不为空, 且不是占位空白对象, 即为有效对象,返回查询结果
            if(product.getProductCode() != null) return mapToProductPriceResponse(product);
            // 非空的空白占位对象,显示不存在,db里面不会有的,对象不合法
            throw new IllegalArgumentException("Product not found: " + productCode);
        }


        // true 代表之前不存在并且当下被设置
        // 锁过期时间应明显长于预估的最大 DB 查询耗时（如 10~30 秒
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey,"1",20,TimeUnit.SECONDS);

        if(Boolean.TRUE.equals(locked)){
            // 获得锁
            try {
                // 1.Double-Check: 重复查一次redis,也许在等待锁的过程中,redis被写入值了
                product = (Product) redisTemplate.opsForValue().get(cacheKey);

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
                    // db查不到,写redis空对象  防穿透 默认过期时间5min短于真正商品缓存30min
                    this.setRedisKV(cacheKey, new Product(),5, TimeUnit.MINUTES);

                    throw new IllegalArgumentException("Product not found: " + productCode);
                }

                product = optionalProduct.get();


                // 3.db查到了,回写redis
                this.setRedisKV(cacheKey, product,cacheTtl, TimeUnit.MILLISECONDS);


                return mapToProductPriceResponse(product);
            } finally {
                // 获得锁的处理结束之后,释放锁
                redisTemplate.delete(lockKey);
            }

        } else {
            // 没有获取到锁,休眠后重试(带重试次数限制)
            try{
                Thread.sleep(LOCK_RETRY_SLEEP_MS);
            } catch(InterruptedException ignored){}

            // 重试(带重试次数限制)
            return getProductPriceWithRetry(productCode, attempt + 1);
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



        // 第一次删除缓存
        redisTemplate.delete(cacheKey);

        // 再删db
        productRepository.deleteById(productCode);

        try{
            sleep(CACHE_DELETE_SLEEP_MS);
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

        // 第一次删除缓存
        redisTemplate.delete(cacheKey);

        // 再改db
        Product savedProduct = productRepository.save(product);

        try{
            sleep(CACHE_DELETE_SLEEP_MS);
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
