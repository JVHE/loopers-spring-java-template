package com.loopers.application.like.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.common.vo.Price;
import com.loopers.domain.like.product.LikeProduct;
import com.loopers.domain.like.product.LikeProductService;
import com.loopers.domain.metrics.product.ProductMetrics;
import com.loopers.domain.metrics.product.ProductMetricsService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.supply.Supply;
import com.loopers.domain.supply.SupplyService;
import com.loopers.domain.supply.vo.Stock;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserService;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 좋아요 이벤트 핸들러 통합 테스트
 * 
 * 이벤트 기반 아키텍처에서:
 * - LikeProductService가 LikeProductEvent를 발행
 * - ProductMetricsService가 이벤트를 받아 likeCount 업데이트 (AFTER_COMMIT, 비동기)
 * - ProductCacheService가 이벤트를 받아 캐시 무효화 (AFTER_COMMIT, 비동기)
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("좋아요 이벤트 핸들러 통합 테스트")
public class LikeEventHandlerIntegrationTest {

    @Autowired
    private LikeProductFacade likeProductFacade;

    @Autowired
    private LikeProductService likeProductService;

    @Autowired
    private ProductMetricsService productMetricsService;

    @Autowired
    private ProductService productService;

    @Autowired
    private BrandService brandService;

    @Autowired
    private SupplyService supplyService;

    @Autowired
    private UserService userService;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private Long brandId;
    private Long productId;
    private String userId;
    private Long userEntityId;

    @BeforeEach
    void setUp() {
        databaseCleanUp.truncateAllTables();

        // Brand 등록
        Brand brand = Brand.create("Nike");
        brand = brandService.save(brand);
        brandId = brand.getId();

        // Product 등록
        Product product = Product.create("테스트 상품", brandId, new Price(10000));
        product = productService.save(product);
        productId = product.getId();

        // ProductMetrics 등록 (초기 좋아요 수: 0)
        ProductMetrics metrics = ProductMetrics.create(productId, brandId, 0);
        productMetricsService.save(metrics);

        // Supply 등록
        Supply supply = Supply.create(productId, new Stock(100));
        supplyService.save(supply);

        // User 등록
        User user = userService.registerUser("user123", "test@test.com", "1993-03-13", "male");
        userId = user.getUserId();
        userEntityId = user.getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("좋아요 등록 이벤트 처리")
    @Nested
    class LikeEventHandling {
        @DisplayName("좋아요 등록 시 ProductMetrics의 likeCount가 비동기로 증가한다")
        @Test
        void should_incrementLikeCount_async_when_likeProduct() throws InterruptedException {
            // arrange
            ProductMetrics initialMetrics = productMetricsService.getMetricsByProductId(productId);
            assertThat(initialMetrics.getLikeCount()).isEqualTo(0);

            // act
            likeProductFacade.likeProduct(userId, productId);

            // assert - 비동기 처리를 위해 대기
            Thread.sleep(2000); // 이벤트 핸들러 처리 대기

            ProductMetrics updatedMetrics = productMetricsService.getMetricsByProductId(productId);
            assertThat(updatedMetrics.getLikeCount()).isEqualTo(1);

            // 실제 LikeProduct 엔티티 개수와 동기화 확인
            Page<LikeProduct> likedProducts = likeProductService.getLikedProducts(
                    userEntityId,
                    PageRequest.of(0, 100)
            );
            long actualLikeCount = likedProducts.getContent().stream()
                    .filter(like -> like.getProductId().equals(productId))
                    .count();
            assertThat(updatedMetrics.getLikeCount()).isEqualTo(actualLikeCount);
        }

        @DisplayName("중복 좋아요 등록 시 likeCount가 증가하지 않는다")
        @Test
        void should_notIncrementLikeCount_when_duplicateLike() throws InterruptedException {
            // arrange
            likeProductFacade.likeProduct(userId, productId);
            Thread.sleep(1000); // 첫 번째 이벤트 처리 대기

            ProductMetrics metricsAfterFirstLike = productMetricsService.getMetricsByProductId(productId);
            assertThat(metricsAfterFirstLike.getLikeCount()).isEqualTo(1);

            // act - 같은 사용자가 다시 좋아요 등록
            likeProductFacade.likeProduct(userId, productId);

            // assert - 비동기 처리 대기
            Thread.sleep(1000);

            ProductMetrics metricsAfterSecondLike = productMetricsService.getMetricsByProductId(productId);
            // 중복 좋아요는 이벤트가 발행되지 않으므로 카운트가 증가하지 않음
            assertThat(metricsAfterSecondLike.getLikeCount()).isEqualTo(1);
        }

        @DisplayName("여러 사용자가 동시에 좋아요를 등록해도 likeCount가 정확히 증가한다")
        @Test
        void should_incrementLikeCount_accurately_when_concurrentLikes() throws InterruptedException {
            // arrange
            int userCount = 5;
            String[] userIds = new String[userCount];
            Long[] userEntityIds = new Long[userCount];

            for (int i = 0; i < userCount; i++) {
                User user = userService.registerUser("user" + i, "user" + i + "@test.com", "1993-03-13", "male");
                userIds[i] = user.getUserId();
                userEntityIds[i] = user.getId();
            }

            ProductMetrics initialMetrics = productMetricsService.getMetricsByProductId(productId);
            assertThat(initialMetrics.getLikeCount()).isEqualTo(0);

            // act - 여러 사용자가 동시에 좋아요 등록
            for (int i = 0; i < userCount; i++) {
                likeProductFacade.likeProduct(userIds[i], productId);
            }

            // assert - 비동기 처리 대기
            Thread.sleep(3000); // 여러 이벤트 처리 대기

            ProductMetrics finalMetrics = productMetricsService.getMetricsByProductId(productId);
            assertThat(finalMetrics.getLikeCount()).isEqualTo(userCount);

            // 실제 LikeProduct 엔티티 개수와 동기화 확인
            long totalLikeCount = 0;
            for (Long userEntityId : userEntityIds) {
                Page<LikeProduct> likedProducts = likeProductService.getLikedProducts(
                        userEntityId,
                        PageRequest.of(0, 100)
                );
                totalLikeCount += likedProducts.getContent().stream()
                        .filter(like -> like.getProductId().equals(productId))
                        .count();
            }
            assertThat(finalMetrics.getLikeCount()).isEqualTo(totalLikeCount);
        }
    }

    @DisplayName("좋아요 취소 이벤트 처리")
    @Nested
    class UnlikeEventHandling {
        @DisplayName("좋아요 취소 시 ProductMetrics의 likeCount가 비동기로 감소한다")
        @Test
        void should_decrementLikeCount_async_when_unlikeProduct() throws InterruptedException {
            // arrange
            likeProductFacade.likeProduct(userId, productId);
            Thread.sleep(1000); // 좋아요 등록 이벤트 처리 대기

            ProductMetrics metricsAfterLike = productMetricsService.getMetricsByProductId(productId);
            assertThat(metricsAfterLike.getLikeCount()).isEqualTo(1);

            // act
            likeProductFacade.unlikeProduct(userId, productId);

            // assert - 비동기 처리 대기
            Thread.sleep(2000);

            ProductMetrics metricsAfterUnlike = productMetricsService.getMetricsByProductId(productId);
            assertThat(metricsAfterUnlike.getLikeCount()).isEqualTo(0);

            // 실제 LikeProduct 엔티티 개수와 동기화 확인
            Page<LikeProduct> likedProducts = likeProductService.getLikedProducts(
                    userEntityId,
                    PageRequest.of(0, 100)
            );
            long actualLikeCount = likedProducts.getContent().stream()
                    .filter(like -> like.getProductId().equals(productId))
                    .count();
            assertThat(metricsAfterUnlike.getLikeCount()).isEqualTo(actualLikeCount);
        }

        @DisplayName("좋아요가 없는 상태에서 취소하면 likeCount가 감소하지 않는다")
        @Test
        void should_notDecrementLikeCount_when_notLiked() throws InterruptedException {
            // arrange
            ProductMetrics initialMetrics = productMetricsService.getMetricsByProductId(productId);
            assertThat(initialMetrics.getLikeCount()).isEqualTo(0);

            // act - 좋아요 없이 취소
            likeProductFacade.unlikeProduct(userId, productId);

            // assert - 비동기 처리 대기
            Thread.sleep(1000);

            ProductMetrics metricsAfterUnlike = productMetricsService.getMetricsByProductId(productId);
            // 좋아요가 없으면 이벤트가 발행되지 않으므로 카운트가 감소하지 않음
            assertThat(metricsAfterUnlike.getLikeCount()).isEqualTo(0);
        }

        @DisplayName("여러 번 좋아요 등록/취소 시 likeCount가 정확히 반영된다")
        @Test
        void should_reflectLikeCount_accurately_when_multipleLikeAndUnlike() throws InterruptedException {
            // arrange
            ProductMetrics initialMetrics = productMetricsService.getMetricsByProductId(productId);
            assertThat(initialMetrics.getLikeCount()).isEqualTo(0);

            // act & assert
            // 좋아요 3개 등록
            likeProductFacade.likeProduct(userId, productId);
            Thread.sleep(500);
            likeProductFacade.likeProduct(userId, productId); // 중복 (카운트 증가 안 함)
            Thread.sleep(500);
            
            // 다른 사용자 2명 추가
            User user2 = userService.registerUser("user2", "user2@test.com", "1993-03-13", "male");
            User user3 = userService.registerUser("user3", "user3@test.com", "1993-03-13", "male");
            likeProductFacade.likeProduct(user2.getUserId(), productId);
            Thread.sleep(500);
            likeProductFacade.likeProduct(user3.getUserId(), productId);
            Thread.sleep(2000); // 이벤트 처리 대기

            ProductMetrics metricsAfter3Likes = productMetricsService.getMetricsByProductId(productId);
            assertThat(metricsAfter3Likes.getLikeCount()).isEqualTo(3);

            // 좋아요 2개 취소
            likeProductFacade.unlikeProduct(userId, productId);
            Thread.sleep(500);
            likeProductFacade.unlikeProduct(user2.getUserId(), productId);
            Thread.sleep(2000); // 이벤트 처리 대기

            ProductMetrics metricsAfter2Unlikes = productMetricsService.getMetricsByProductId(productId);
            assertThat(metricsAfter2Unlikes.getLikeCount()).isEqualTo(1);

            // 좋아요 1개 더 등록
            User user4 = userService.registerUser("user4", "user4@test.com", "1993-03-13", "male");
            likeProductFacade.likeProduct(user4.getUserId(), productId);
            Thread.sleep(2000); // 이벤트 처리 대기

            ProductMetrics finalMetrics = productMetricsService.getMetricsByProductId(productId);
            assertThat(finalMetrics.getLikeCount()).isEqualTo(2);
        }
    }

    @DisplayName("여러 상품에 대한 좋아요 이벤트 처리")
    @Nested
    class MultipleProductsEventHandling {
        @DisplayName("여러 상품에 좋아요를 등록할 때 각 상품의 likeCount가 정확히 증가한다")
        @Test
        void should_incrementLikeCount_accurately_for_multipleProducts() throws InterruptedException {
            // arrange
            Product product2 = Product.create("테스트 상품2", brandId, new Price(20000));
            product2 = productService.save(product2);
            Long productId2 = product2.getId();

            ProductMetrics metrics2 = ProductMetrics.create(productId2, brandId, 0);
            productMetricsService.save(metrics2);

            Supply supply2 = Supply.create(productId2, new Stock(50));
            supplyService.save(supply2);

            ProductMetrics initialMetrics1 = productMetricsService.getMetricsByProductId(productId);
            ProductMetrics initialMetrics2 = productMetricsService.getMetricsByProductId(productId2);
            assertThat(initialMetrics1.getLikeCount()).isEqualTo(0);
            assertThat(initialMetrics2.getLikeCount()).isEqualTo(0);

            // act
            likeProductFacade.likeProduct(userId, productId);
            likeProductFacade.likeProduct(userId, productId2);

            // assert - 비동기 처리 대기
            Thread.sleep(2000);

            ProductMetrics finalMetrics1 = productMetricsService.getMetricsByProductId(productId);
            ProductMetrics finalMetrics2 = productMetricsService.getMetricsByProductId(productId2);

            assertThat(finalMetrics1.getLikeCount()).isEqualTo(1);
            assertThat(finalMetrics2.getLikeCount()).isEqualTo(1);
        }
    }
}

