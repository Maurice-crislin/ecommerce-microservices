package org.example.searchservice.mq.consumer;

import org.common.mq.events.ProductCreatedEvent;
import org.common.mq.events.ProductDeletedEvent;
import org.common.mq.events.ProductUpdatedEvent;
import org.common.product.enums.CategoryCode;
import org.common.product.enums.ProductStatus;
import org.example.searchservice.document.ProductDoc;
import org.example.searchservice.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Unit tests for {@link ProductSyncConsumer}.
 * <p>
 * Verifies the core message consumption flow:
 * <ul>
 *   <li>{@link ProductSyncConsumer#handleCreated(ProductCreatedEvent)}</li>
 *   <li>{@link ProductSyncConsumer#handleUpdated(ProductUpdatedEvent)}</li>
 *   <li>{@link ProductSyncConsumer#handleDeleted(ProductDeletedEvent)}</li>
 * </ul>
 * Uses real {@code ProductDocMapper} to ensure end-to-end mapping correctness,
 * while mocking the {@link ProductRepository} to isolate the consumer logic.
 */
@ExtendWith(MockitoExtension.class)
class ProductSyncConsumerTest {

    @Mock
    private ProductRepository productRepository;

    @Captor
    private ArgumentCaptor<ProductDoc> productDocCaptor;

    @Captor
    private ArgumentCaptor<Long> longCaptor;

    private ProductSyncConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProductSyncConsumer(productRepository);
    }

    // =========================================================================
    // ProductCreatedEvent consumption
    // =========================================================================

    @Nested
    @DisplayName("handleCreated(ProductCreatedEvent)")
    class HandleCreatedTests {

        @Test
        @DisplayName("Should map event to ProductDoc and save to repository")
        void shouldSaveProductDocOnCreatedEvent() {
            // Given
            ProductCreatedEvent event = new ProductCreatedEvent(
                    1001L,
                    "MacBook Pro",
                    BigDecimal.valueOf(2499.99),
                    ProductStatus.ACTIVE,
                    "High performance laptop",
                    "Apple",
                    CategoryCode.LAPTOP
            );

            // When
            consumer.handleCreated(event);

            // Then: verify save was called with the correct mapped ProductDoc
            verify(productRepository).save(productDocCaptor.capture());
            ProductDoc savedDoc = productDocCaptor.getValue();

            assertThat(savedDoc.getProductCode()).isEqualTo(1001L);
            assertThat(savedDoc.getProductName()).isEqualTo("MacBook Pro");
            assertThat(savedDoc.getPrice()).isEqualTo(2499.99);
            assertThat(savedDoc.getStatus()).isEqualTo(ProductStatus.ACTIVE);
            assertThat(savedDoc.getDescription()).isEqualTo("High performance laptop");
            assertThat(savedDoc.getBrand()).isEqualTo("Apple");
            assertThat(savedDoc.getCategoryCode()).isEqualTo(CategoryCode.LAPTOP);

            verifyNoMoreInteractions(productRepository);
        }

        @Test
        @DisplayName("Should handle event with null price (default to 0)")
        void shouldHandleNullPrice() {
            // Given
            ProductCreatedEvent event = new ProductCreatedEvent(
                    1002L,
                    "Free Product",
                    null,
                    ProductStatus.INACTIVE,
                    "Free item",
                    "Generic",
                    CategoryCode.ELECTRONICS
            );

            // When
            consumer.handleCreated(event);

            // Then
            verify(productRepository).save(productDocCaptor.capture());
            assertThat(productDocCaptor.getValue().getPrice()).isZero();
            assertThat(productDocCaptor.getValue().getStatus()).isEqualTo(ProductStatus.INACTIVE);

            verifyNoMoreInteractions(productRepository);
        }

        @Test
        @DisplayName("Should handle event with all null text fields")
        void shouldHandleNullTextFields() {
            // Given
            ProductCreatedEvent event = new ProductCreatedEvent(
                    1003L,
                    null,           // productName
                    BigDecimal.TEN,
                    ProductStatus.ACTIVE,
                    null,           // description
                    null,           // brand
                    CategoryCode.UNKNOWN
            );

            // When
            consumer.handleCreated(event);

            // Then
            verify(productRepository).save(productDocCaptor.capture());
            ProductDoc doc = productDocCaptor.getValue();
            assertThat(doc.getProductCode()).isEqualTo(1003L);
            assertThat(doc.getProductName()).isNull();
            assertThat(doc.getDescription()).isNull();
            assertThat(doc.getBrand()).isNull();
            assertThat(doc.getCategoryCode()).isEqualTo(CategoryCode.UNKNOWN);
            assertThat(doc.getPrice()).isEqualTo(10.0);

            verifyNoMoreInteractions(productRepository);
        }

        @Test
        @DisplayName("Should preserve full numeric precision for price")
        void shouldPreservePricePrecision() {
            // Given
            ProductCreatedEvent event = new ProductCreatedEvent(
                    1004L,
                    "Precision Item",
                    BigDecimal.valueOf(1234.5678),
                    ProductStatus.ACTIVE,
                    "desc",
                    "brand",
                    CategoryCode.ELECTRONICS
            );

            // When
            consumer.handleCreated(event);

            // Then
            verify(productRepository).save(productDocCaptor.capture());
            assertThat(productDocCaptor.getValue().getPrice()).isEqualTo(1234.5678);

            verifyNoMoreInteractions(productRepository);
        }

        @Test
        @DisplayName("Should handle all CategoryCode enum values")
        void shouldHandleAllCategoryCodes() {
            // Given
            CategoryCode[] codes = CategoryCode.values();
            ProductCreatedEvent[] events = new ProductCreatedEvent[codes.length];
            for (int i = 0; i < codes.length; i++) {
                events[i] = new ProductCreatedEvent(
                        2000L + i,
                        "Product",
                        BigDecimal.valueOf(100),
                        ProductStatus.ACTIVE,
                        "desc",
                        "brand",
                        codes[i]
                );
            }

            // When
            for (ProductCreatedEvent event : events) {
                consumer.handleCreated(event);
            }

            // Then
            verify(productRepository, times(codes.length)).save(productDocCaptor.capture());
            assertThat(productDocCaptor.getValue().getCategoryCode()).isEqualTo(codes[codes.length - 1]);

            verifyNoMoreInteractions(productRepository);
        }

        @Test
        @DisplayName("Should handle all ProductStatus enum values")
        void shouldHandleAllProductStatuses() {
            // Given
            ProductStatus[] statuses = ProductStatus.values();
            ProductCreatedEvent[] events = new ProductCreatedEvent[statuses.length];
            for (int i = 0; i < statuses.length; i++) {
                events[i] = new ProductCreatedEvent(
                        3000L + i,
                        "Product",
                        BigDecimal.TEN,
                        statuses[i],
                        "desc",
                        "brand",
                        CategoryCode.ELECTRONICS
                );
            }

            // When
            for (ProductCreatedEvent event : events) {
                consumer.handleCreated(event);
            }

            // Then
            verify(productRepository, times(statuses.length)).save(productDocCaptor.capture());
            assertThat(productDocCaptor.getValue().getStatus()).isEqualTo(statuses[statuses.length - 1]);

            verifyNoMoreInteractions(productRepository);
        }
    }

    // =========================================================================
    // ProductUpdatedEvent consumption
    // =========================================================================

    @Nested
    @DisplayName("handleUpdated(ProductUpdatedEvent)")
    class HandleUpdatedTests {

        @Test
        @DisplayName("Should map event to ProductDoc and save to repository")
        void shouldSaveProductDocOnUpdatedEvent() {
            // Given
            ProductUpdatedEvent event = new ProductUpdatedEvent(
                    2001L,
                    "Galaxy S25",
                    BigDecimal.valueOf(1299.0),
                    ProductStatus.ACTIVE,
                    "Samsung flagship phone",
                    "Samsung",
                    CategoryCode.SMARTPHONE
            );

            // When
            consumer.handleUpdated(event);

            // Then
            verify(productRepository).save(productDocCaptor.capture());
            ProductDoc savedDoc = productDocCaptor.getValue();

            assertThat(savedDoc.getProductCode()).isEqualTo(2001L);
            assertThat(savedDoc.getProductName()).isEqualTo("Galaxy S25");
            assertThat(savedDoc.getPrice()).isEqualTo(1299.0);
            assertThat(savedDoc.getStatus()).isEqualTo(ProductStatus.ACTIVE);
            assertThat(savedDoc.getDescription()).isEqualTo("Samsung flagship phone");
            assertThat(savedDoc.getBrand()).isEqualTo("Samsung");
            assertThat(savedDoc.getCategoryCode()).isEqualTo(CategoryCode.SMARTPHONE);

            verifyNoMoreInteractions(productRepository);
        }

        @Test
        @DisplayName("Should handle event with null price (default to 0)")
        void shouldHandleNullPrice() {
            // Given
            ProductUpdatedEvent event = new ProductUpdatedEvent(
                    2002L,
                    "Free Update",
                    null,
                    ProductStatus.INACTIVE,
                    "Updated to free",
                    "Generic",
                    CategoryCode.ELECTRONICS
            );

            // When
            consumer.handleUpdated(event);

            // Then
            verify(productRepository).save(productDocCaptor.capture());
            assertThat(productDocCaptor.getValue().getPrice()).isZero();

            verifyNoMoreInteractions(productRepository);
        }

        @Test
        @DisplayName("Should handle event with partial fields (only productCode changed)")
        void shouldHandlePartialUpdate() {
            // Given
            ProductUpdatedEvent event = new ProductUpdatedEvent(
                    2003L,
                    "Price Changed Only",
                    BigDecimal.valueOf(99.99),
                    ProductStatus.ACTIVE,
                    null,           // description unchanged
                    null,           // brand unchanged
                    null            // categoryCode unchanged
            );

            // When
            consumer.handleUpdated(event);

            // Then
            verify(productRepository).save(productDocCaptor.capture());
            ProductDoc doc = productDocCaptor.getValue();
            assertThat(doc.getProductCode()).isEqualTo(2003L);
            assertThat(doc.getProductName()).isEqualTo("Price Changed Only");
            assertThat(doc.getPrice()).isEqualTo(99.99);
            assertThat(doc.getStatus()).isEqualTo(ProductStatus.ACTIVE);
            assertThat(doc.getDescription()).isNull();
            assertThat(doc.getBrand()).isNull();
            assertThat(doc.getCategoryCode()).isNull();

            verifyNoMoreInteractions(productRepository);
        }

        @Test
        @DisplayName("Should preserve full price precision")
        void shouldPreservePricePrecision() {
            // Given
            ProductUpdatedEvent event = new ProductUpdatedEvent(
                    2004L,
                    "Precision Update",
                    BigDecimal.valueOf(0.001),
                    ProductStatus.ACTIVE,
                    "desc",
                    "brand",
                    CategoryCode.ELECTRONICS
            );

            // When
            consumer.handleUpdated(event);

            // Then
            verify(productRepository).save(productDocCaptor.capture());
            assertThat(productDocCaptor.getValue().getPrice()).isEqualTo(0.001);

            verifyNoMoreInteractions(productRepository);
        }
    }

    // =========================================================================
    // ProductDeletedEvent consumption
    // =========================================================================

    @Nested
    @DisplayName("handleDeleted(ProductDeletedEvent)")
    class HandleDeletedTests {

        @Test
        @DisplayName("Should delete ProductDoc by productCode")
        void shouldDeleteByProductCode() {
            // Given
            ProductDeletedEvent event = new ProductDeletedEvent(5001L);

            // When
            consumer.handleDeleted(event);

            // Then
            verify(productRepository).deleteByProductCode(5001L);
            verifyNoMoreInteractions(productRepository);
        }

        @Test
        @DisplayName("Should handle large productCode values")
        void shouldHandleLargeProductCode() {
            // Given
            long largeCode = Long.MAX_VALUE;
            ProductDeletedEvent event = new ProductDeletedEvent(largeCode);

            // When
            consumer.handleDeleted(event);

            // Then
            verify(productRepository).deleteByProductCode(largeCode);
            verifyNoMoreInteractions(productRepository);
        }

        @Test
        @DisplayName("Should handle zero productCode")
        void shouldHandleZeroProductCode() {
            // Given
            ProductDeletedEvent event = new ProductDeletedEvent(0L);

            // When
            consumer.handleDeleted(event);

            // Then
            verify(productRepository).deleteByProductCode(0L);
            verifyNoMoreInteractions(productRepository);
        }

        @Test
        @DisplayName("Should handle negative productCode (edge case)")
        void shouldHandleNegativeProductCode() {
            // Given
            ProductDeletedEvent event = new ProductDeletedEvent(-1L);

            // When
            consumer.handleDeleted(event);

            // Then
            verify(productRepository).deleteByProductCode(-1L);
            verifyNoMoreInteractions(productRepository);
        }
    }
}