package org.example.searchservice.service.impl;


import lombok.RequiredArgsConstructor;
import org.example.searchservice.document.ProductDoc;
import org.example.searchservice.dto.ProductSearchRequest;
import org.example.searchservice.service.ProductSearchService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductSearchServiceImpl implements ProductSearchService {

    private final ElasticsearchOperations elasticsearchOperations;

    @Override
    public List<ProductDoc> searchProducts(ProductSearchRequest request){

        Criteria criteria = new Criteria();

        // 关键词：在 productName 或 description 字段里模糊匹配 contains
        // 或 nameCriteria.or(descriptionCriteria)
        if(request.getKeyword()!=null && !request.getKeyword().isEmpty()){
            String keyword = request.getKeyword();
            Criteria nameCriteria = new Criteria("productName").contains(keyword);
            Criteria descriptionCriteria = new Criteria("description").contains(keyword);


            criteria = criteria.and(nameCriteria.or(descriptionCriteria));
        }
        // 价格区间：between(最小值, 最大值)
        if(request.getMinPrice()!=null && request.getMaxPrice()!=null){
            criteria = criteria.and(new Criteria("price").between(request.getMinPrice(),request.getMaxPrice()));
        } else if(request.getMinPrice()!=null){
            criteria = criteria.and(new Criteria("price").greaterThanEqual(request.getMinPrice()));
        } else if(request.getMaxPrice()!=null){
            criteria = criteria.and(new Criteria("price").lessThanEqual(request.getMaxPrice()));
        }

        // 品牌精确匹配 is
        if(request.getBrand()!=null){
            criteria = criteria.and(new Criteria("brand").is(request.getBrand()));
        }

        // 分类精确匹配
        if(request.getCategoryCode()!=null){
            criteria = criteria.and(new Criteria("categoryCode").is(request.getCategoryCode()));
        }

        // 设置分页 默认 0 20
        int page = request.getPage()!=null?request.getPage():0;
        int size = request.getSize()!=null?request.getSize():20;
        PageRequest pageRequest = PageRequest.of(page,size);


        // 把 Criteria 和分页信息组装成 CriteriaQuery
        // 先设置criteria
        CriteriaQuery query = new CriteriaQuery(criteria);
        // 再设置分页
        query.setPageable(pageRequest);


        // 执行查询，拿到结果
        SearchHits<ProductDoc> searchHits = elasticsearchOperations.search(query,ProductDoc.class);

        return searchHits.stream()
                .map(SearchHit::getContent)
                .toList();

    }
}
