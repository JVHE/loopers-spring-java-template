package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.metrics.product.ProductMetrics;
import com.loopers.domain.metrics.product.ProductMetricsService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.supply.Supply;
import com.loopers.domain.supply.SupplyService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class ProductFacade {
    private final ProductService productService;
    private final ProductMetricsService productMetricsService;
    private final BrandService brandService;
    private final SupplyService supplyService;

    @Transactional
    public ProductInfo createProduct(ProductCreateRequest request) {
        Brand brand = brandService.getBrandById(request.brandId());

        Product product = Product.create(request.name(), brand.getId(), request.price());
        product = productService.save(product);

        productMetricsService.save(ProductMetrics.create(product.getId(), request.likeCount()));
        supplyService.save(Supply.create(product.getId(), request.stock()));

        return new ProductInfo(
                product.getId(),
                product.getName(),
                brand.getName(),
                product.getPrice().amount(),
                0,
                request.stock().quantity()
        );
    }

    @Transactional
    public List<ProductInfo> createProductBulk(List<ProductCreateRequest> requests) {
        List<Long> brandIds = requests.stream().map(ProductCreateRequest::brandId).distinct().toList();
        Map<Long, Brand> brandMap = brandService.getBrandMapByBrandIds(brandIds);
        if (brandMap.size() != brandIds.size()) {
            throw new CoreException(ErrorType.NOT_FOUND, "일부 브랜드를 찾을 수 없습니다.");
        }

        List<Product> products = productService.saveAll(requests.stream()
                .map(req -> Product.create(req.name(), req.brandId(), req.price()))
                .toList()
        );
        Map<ProductCreateRequest, Product> requestProductMap = new HashMap<>();
        for (int i = 0; i < requests.size(); i++) {
            requestProductMap.put(requests.get(i), products.get(i));
        }

        productMetricsService.saveAll(
                requestProductMap.entrySet().stream()
                        .map(entry -> ProductMetrics.create(entry.getValue().getId(), entry.getKey().likeCount()))
                        .toList()
        );
        supplyService.saveAll(
                requestProductMap.entrySet().stream()
                        .map(entry -> Supply.create(entry.getValue().getId(), entry.getKey().stock()))
                        .toList()
        );

        return requestProductMap.entrySet().stream()
                .map(entry -> {
                    ProductCreateRequest req = entry.getKey();
                    Product product = entry.getValue();
                    Brand brand = brandMap.get(product.getBrandId());
                    return new ProductInfo(
                            product.getId(),
                            product.getName(),
                            brand.getName(),
                            product.getPrice().amount(),
                            req.likeCount(),
                            req.stock().quantity()
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<ProductInfo> getProductList(ProductSearchRequest request) {
        Pageable pageable = request.pageable();
        String sortStr = pageable.getSort().toString().split(":")[0];
        if (StringUtils.equals(sortStr, "like_desc")) {
            int page = pageable.getPageNumber();
            int size = pageable.getPageSize();
            Sort sort = Sort.by(Sort.Direction.DESC, "likeCount");
            return getProductsByLikeCount(PageRequest.of(page, size, sort));
        }

        Page<Product> products = productService.getProducts(pageable);

        List<Long> productIds = products.map(Product::getId).toList();
        Set<Long> brandIds = products.map(Product::getBrandId).toSet();

        Map<Long, ProductMetrics> metricsMap = productMetricsService.getMetricsMapByProductIds(productIds);
        Map<Long, Supply> supplyMap = supplyService.getSupplyMapByProductIds(productIds);
        Map<Long, Brand> brandMap = brandService.getBrandMapByBrandIds(brandIds);

        return products.map(product -> {
            ProductMetrics metrics = metricsMap.get(product.getId());
            if (metrics == null) {
                throw new CoreException(ErrorType.NOT_FOUND, "해당 상품의 메트릭 정보를 찾을 수 없습니다.");
            }
            Brand brand = brandMap.get(product.getBrandId());
            if (brand == null) {
                throw new CoreException(ErrorType.NOT_FOUND, "해당 상품의 브랜드 정보를 찾을 수 없습니다.");
            }
            Supply supply = supplyMap.get(product.getId());
            if (supply == null) {
                throw new CoreException(ErrorType.NOT_FOUND, "해당 상품의 재고 정보를 찾을 수 없습니다.");
            }

            return new ProductInfo(
                    product.getId(),
                    product.getName(),
                    brand.getName(),
                    product.getPrice().amount(),
                    metrics.getLikeCount(),
                    supply.getStock().quantity()
            );
        });
    }

    public Page<ProductInfo> getProductsByLikeCount(Pageable pageable) {
        Page<ProductMetrics> metricsPage = productMetricsService.getMetrics(pageable);
        List<Long> productIds = metricsPage.map(ProductMetrics::getProductId).toList();
        Map<Long, Product> productMap = productService.getProductMapByIds(productIds);
        Set<Long> brandIds = productMap.values().stream().map(Product::getBrandId).collect(Collectors.toSet());
        Map<Long, Brand> brandMap = brandService.getBrandMapByBrandIds(brandIds);
        Map<Long, Supply> supplyMap = supplyService.getSupplyMapByProductIds(productIds);

        return metricsPage.map(metrics -> {
            Product product = productMap.get(metrics.getProductId());
            if (product == null) {
                throw new CoreException(ErrorType.NOT_FOUND, "해당 상품 정보를 찾을 수 없습니다.");
            }
            Brand brand = brandMap.get(product.getBrandId());
            if (brand == null) {
                throw new CoreException(ErrorType.NOT_FOUND, "해당 상품의 브랜드 정보를 찾을 수 없습니다.");
            }
            Supply supply = supplyMap.get(product.getId());
            if (supply == null) {
                throw new CoreException(ErrorType.NOT_FOUND, "해당 상품의 재고 정보를 찾을 수 없습니다.");
            }

            return new ProductInfo(
                    product.getId(),
                    product.getName(),
                    brand.getName(),
                    product.getPrice().amount(),
                    metrics.getLikeCount(),
                    supply.getStock().quantity()
            );
        });
    }

    @Transactional(readOnly = true)
    public ProductInfo getProductDetail(Long productId) {
        Product product = productService.getProductById(productId);
        ProductMetrics metrics = productMetricsService.getMetricsByProductId(productId);
        Brand brand = brandService.getBrandById(product.getBrandId());
        Supply supply = supplyService.getSupplyByProductId(productId);

        return new ProductInfo(
                productId,
                product.getName(),
                brand.getName(),
                product.getPrice().amount(),
                metrics.getLikeCount(),
                supply.getStock().quantity()
        );
    }
}
