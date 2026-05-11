package org.example.productservice.cache;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.example.productservice.repository.ProductRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProductBloomFilter {
    private final BloomFilter<Long> bloomFilter;

    public ProductBloomFilter(ProductRepository productRepository) {
        // 100000 预期元素数, 0.01 假阳性率
        this.bloomFilter = BloomFilter.create(Funnels.longFunnel(), 100000, 0.01);

        List<Long> productCodes = productRepository.findAllProductCodes();

        for (Long productCode : productCodes) {
            bloomFilter.put(productCode);
        }

    }
    public boolean mightContain(long productCode) {
        return bloomFilter.mightContain(productCode);
    }
    public void add(long productCode) {
        bloomFilter.put(productCode);
    }
}
