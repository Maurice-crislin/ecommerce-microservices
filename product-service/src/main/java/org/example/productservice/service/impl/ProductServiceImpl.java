package org.example.productservice.service.impl;


import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.common.product.enums.ProductStatus;
import org.example.productservice.cache.ProductBloomFilter;
import org.example.productservice.dto.*;
import org.example.productservice.entity.Product;
import org.example.productservice.entity.ProductDetail;
import org.example.productservice.mapper.ProductEventMapper;
import org.example.productservice.mq.producer.ProductSyncProducer;
import org.example.productservice.repository.ProductRepository;
import org.example.productservice.service.ProductService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {
    private final ProductRepository productRepository;
    private final RedisTemplate<String,Object> redisTemplate;
    private final ProductBloomFilter productBloomFilter;
    private final ProductEventMapper  productEventMapper;
    private final ProductSyncProducer productSyncProducer;

    private static final String PRODUCT_DETAIL_PREFIX = "product:detail:";
    private static final String PRODUCT_LOCK_PREFIX = "product:lock:";
    private static final int LOCK_RETRY_MAX_ATTEMPTS = 5;
    private static final long LOCK_RETRY_SLEEP_MS = 50;
    private static final long CACHE_DELETE_SLEEP_MS = 100;

    @Value("${product.cache.ttl:1800000}")
    // 真正商品缓存时间
    private int cacheTtl;

    private void setRedisKV(String key, Object value, long timeout, TimeUnit unit) {
        try{
            redisTemplate.opsForValue().set(key, value, timeout, unit);
        } catch(Exception e){
            e.printStackTrace();
        }
    }

    private void sleep(long millis) {
        try{
            Thread.sleep(millis);
        } catch(InterruptedException ignored){}
    }

    private Product tryGetProductFromRedis(String cacheKey){
        try{
            return (Product) redisTemplate.opsForValue().get(cacheKey);
        } catch (Exception e) {
            e.printStackTrace();
            // redis查询出错, product保持null, 后续走锁+DB查询路径
            return null;
        }
    }
    private Optional<ProductPriceResponse> checkRedisCacheHit(long productCode,Product product) {
        // 空对象,走后续锁和db逻辑
        if(product == null) return Optional.empty();

        // product自身不为空, 且不是占位空白对象, 即为有效对象
        if(product.getProductCode() != null) return Optional.of(this.mapToProductPriceResponse(product));
        else{
            // 非空的空白占位对象,显示不存在,db里面不会有的,对象不合法
            throw new IllegalArgumentException("Product not found " + productCode);
        }
    }


    @Override
    public ProductPriceResponse getProductPrice(Long productCode){
        if(productCode == null){
            throw new IllegalArgumentException("Product code is null");
        }
        if(!productBloomFilter.mightContain(productCode)){
            throw new IllegalArgumentException("Product not found:" + productCode);
        }
        return getProductPriceWithRetry(productCode, 0);
    }

    /**
     * 带重试上限的缓存查询方法,避免递归溢出
     */
    private ProductPriceResponse getProductPriceWithRetry(Long productCode, int attempt){
        if(attempt >= LOCK_RETRY_MAX_ATTEMPTS){
            throw new RuntimeException("Failed to acquire lock for product: " + productCode + " after " + LOCK_RETRY_MAX_ATTEMPTS + " attempts");
        }

        String cacheKey = PRODUCT_DETAIL_PREFIX + productCode;
        String lockKey = PRODUCT_LOCK_PREFIX + productCode;

        // 1.查redis
        Product product = null;

        product = this.tryGetProductFromRedis(cacheKey);

        Optional<ProductPriceResponse> productPriceResponse = this.checkRedisCacheHit(productCode, product);

        if(productPriceResponse.isPresent())return productPriceResponse.get();


        // true 代表之前不存在并且当下被设置
        // 锁过期时间应明显长于预估的最大 DB 查询耗时（如 10~30 秒
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey,"1",20,TimeUnit.SECONDS);

        if(Boolean.TRUE.equals(locked)){
            // 获得锁的才能去db查,防止Hotspot Invalid (某热点key失效,大量相同请求达到db)
            try {
                // 1.Double-Check: 重复查一次redis,也许在等待锁的过程中,redis被写入值了
                product = this.tryGetProductFromRedis(cacheKey);

                productPriceResponse = this.checkRedisCacheHit(productCode, product);

                if(productPriceResponse.isPresent())return productPriceResponse.get();



                // 2.查db
                Optional<Product> optionalProduct = productRepository
                        .findProductByProductCode(productCode);

                if(optionalProduct.isEmpty()){
                    // db查不到,写redis空对象  防穿透 默认过期时间5min短于真正商品缓存30min
                    this.setRedisKV(cacheKey, new Product(),5, TimeUnit.MINUTES);

                    throw new IllegalArgumentException("Product not found: " + productCode);
                }

                product = optionalProduct.get();

                ProductCache productCache = mapToProductCache(product);

                // 3.db查到了,把不含detail的数据 回写redis
                this.setRedisKV(cacheKey, product, cacheTtl, TimeUnit.MILLISECONDS);


                return mapToProductPriceResponse(product);
            } finally {
                // 获得锁的处理结束之后,释放锁
                redisTemplate.delete(lockKey);
            }

        } else {
            // 没有获取到锁,休眠后重试(带重试次数限制)
            this.sleep(LOCK_RETRY_SLEEP_MS);
            // 重试(带重试次数限制)
            return getProductPriceWithRetry(productCode, attempt + 1);
        }

    }
   private Product extractProduct(List<Object> cachedValues, int index) {

        if (cachedValues == null) {
            return null;
        }

        if (index >= cachedValues.size()) return null;

        Object raw = cachedValues.get(index);

        if (!(raw instanceof Product)) {
            return null;
        }

        return (Product) raw;
    }

    /**
     * 被 getBatchProductPrices 调用,属于基础方法, 只负责批量查询数据并转换为 DTO
     * 不对接任何外部调用端点
     * 不需要分布式锁（批量场景逐条加锁性能太差），直接用 __批量读 + 批量回写__。
     * @param productCodes
     * @return
     */
    @Override
    public List<ProductPriceResponse> getProductPrices(List<Long> productCodes) {

        // 0.bloom filter
        productCodes = productCodes
                .stream()
                .distinct()
                .filter(productBloomFilter::mightContain)
                .toList();
        if(productCodes.isEmpty()) return Collections.emptyList();


        // 1: 批量读 Redis
        List<String> cacheKeys = productCodes.stream()
                .map(code -> PRODUCT_DETAIL_PREFIX + code)
                .toList();
        List<Object> cachedValues = redisTemplate.opsForValue().multiGet(cacheKeys);

        // 2: 遍历分类
        List<Long> missCodes = new ArrayList<>();
        Map<Long, ProductPriceResponse> hitMap = new HashMap<>();

        for (int i = 0; i < productCodes.size(); i++) {
            Long code = productCodes.get(i);
            Product product = extractProduct(cachedValues, i);

            if (product == null) {
                missCodes.add(code);  // redis查不到,之后去db查
            } else if (product.getProductCode() != null) { // 有效
                hitMap.put(code, mapToProductPriceResponse(product));  // 缓存命中
            }
            // 空占位 → 跳过,不加入结果
        }

        // 3: 批量查 DB
        if (!missCodes.isEmpty()) {
            // redis没有的查db
            List<Product> dbProducts = productRepository.findProductsByProductCodeIn(missCodes);

            // 查到 DB 的 → 回写缓存 + 加入结果
            for (Product p : dbProducts) {
                hitMap.put(p.getProductCode(), mapToProductPriceResponse(p));
                this.setRedisKV(PRODUCT_DETAIL_PREFIX + p.getProductCode(), p, cacheTtl, TimeUnit.MILLISECONDS);
            }


            Set<Long> dbFoundCodes = dbProducts.stream()
                    .map(Product::getProductCode)
                    .collect(Collectors.toSet());

            // redis查不到 db也查不到 → 写空值占位防穿透
            for (Long missCode : missCodes) {
                if (!dbFoundCodes.contains(missCode)) {
                    this.setRedisKV(PRODUCT_DETAIL_PREFIX + missCode, new Product(), 5, TimeUnit.MINUTES);
                }
            }
        }

        // 4: 按输入顺序返回
        return productCodes.stream()
                .map(hitMap::get)
                .filter(Objects::nonNull)
                .toList();
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
    @Transactional
    @Override
    public void deleteProduct(Long productCode) {
        String cacheKey = PRODUCT_DETAIL_PREFIX + productCode;

        // 先检查商品是否存在 不存在直接退出 不用删除redis缓存
        Product product = productRepository.findProductByProductCode(productCode)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productCode));

        // 第一次删除缓存
        redisTemplate.delete(cacheKey);

        // 再删db (配置了 CascadeType.REMOVE，这里一删，两张表都删了)
        productRepository.delete(product);

        productSyncProducer.publishDeleteProductEvent(
                productEventMapper.toDeletedEvent(product)
        );


        this.sleep(CACHE_DELETE_SLEEP_MS);
        // 第二次删除缓存（确保清空可能被并发读线程写入的旧数据）
        redisTemplate.delete(cacheKey);
    }

    @Override
    public ProductResponse addProduct(ProductCreateRequest productCreateRequest){
        // 使用时间戳生成唯一商品编码
        long productCode = System.currentTimeMillis();
        // 主表
        Product product = new Product();
        product.setProductCode(productCode);
        product.setProductName(productCreateRequest.getProductName());
        product.setPrice(productCreateRequest.getPrice());

        // detail表
        ProductDetail detail = new ProductDetail();
        detail.setBrand(productCreateRequest.getBrand());
        detail.setCategoryCode(productCreateRequest.getCategoryCode());
        detail.setDescription(productCreateRequest.getDescription());

        // 建立双向关联
        product.setProductDetail(detail);

        // 保存
        Product savedProduct = productRepository.save(product);

        productSyncProducer.publishCreateProductEvent(
                productEventMapper.toCreateEvent(savedProduct)
        );

        // 将新商品编码加入布隆过滤器，防止后续查询误判为不存在
        productBloomFilter.add(savedProduct.getProductCode());

        return  mapToProductResponse(savedProduct);
    }

    /**
     * 先查 → 再改 → 再存 → 再转 DTO
     * transition不影响功能
     * 有 @Transactional → Product保持Persistent，save()只flush（直接UPDATE）。差别就是少一次 SELECT，性能更好
     * 无 @Transactional → Product变成Detached，save()走merge（先SELECT再UPDATE）。
     */
    @Transactional
    @Override
    public ProductResponse updateProduct(Long productCode, ProductUpdateRequest productUpdateRequest){
        String cacheKey = PRODUCT_DETAIL_PREFIX + productCode;

        Product product = productRepository.findProductByProductCode(productCode).orElseThrow(() -> new IllegalArgumentException("Product not found: " + productCode));

        if(productUpdateRequest.getPrice() != null){
            product.setPrice(productUpdateRequest.getPrice());
        }

        ProductDetail detail = new ProductDetail();

        if(detail != null){
            if(productUpdateRequest.getBrand() != null){
                detail.setBrand(productUpdateRequest.getBrand());
            }
            if(productUpdateRequest.getCategoryCode() != null){
                detail.setCategoryCode(productUpdateRequest.getCategoryCode());
            }
            if(productUpdateRequest.getDescription() != null){
                detail.setDescription(productUpdateRequest.getDescription());
            }
        }


        // 先删缓存 → 再写DB → sleep → 再删缓存
        // 第一次删除缓存
        redisTemplate.delete(cacheKey);

        // 再改db 两张表都更新了
        Product savedProduct = productRepository.save(product);

        productSyncProducer.publishUpdateProductEvent(
                productEventMapper.toUpdatedEvent(savedProduct)
        );

        this.sleep(CACHE_DELETE_SLEEP_MS);
        // 第二次删除缓存（确保清空可能被并发读线程写入的旧数据）
        redisTemplate.delete(cacheKey);

        return  mapToProductResponse(savedProduct);
    }

    private ProductCache mapToProductCache(Product product){
        return new ProductCache(
                product.getProductCode(),
                product.getProductName(),
                product.getPrice(),
                product.getStatus()
        );
    }

    private ProductResponse mapToProductResponse(Product product){
        return new ProductResponse(
                product.getProductCode(),
                product.getProductName(),
                product.getPrice(),
                product.getStatus(),
                product.getProductDetail().getBrand(),
                product.getProductDetail().getDescription(),
                product.getProductDetail().getCategoryCode()
        );
    }

}