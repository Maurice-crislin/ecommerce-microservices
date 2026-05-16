package org.example.searchservice.mapper;

import org.example.searchservice.document.ProductDoc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.common.mq.events.ProductCreatedEvent;
import org.common.mq.events.ProductUpdatedEvent;
import org.common.product.enums.CategoryCode;
import org.common.product.enums.ProductStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ProductDocMapper}.
 * <p>
 * Verifies the mapping from MQ events (ProductCreatedEvent, ProductUpdatedEvent)
 * to the Elasticsearch ProductDoc document.
 */
class ProductDocMapperTest {

    // =========================================================================
    // ProductCreatedEvent mapping tests
    // =========================================================================

    @Nested
    @DisplayName("ProductCreatedEvent → ProductDoc mapping")
    class CreatedEventMappingTests {

        @Test
        @DisplayName("Should map all fields correctly from ProductCreatedEvent")
        void mapAllFields() {
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
            ProductDoc doc = ProductDocMapper.mapToDoc(event);

            // Then
            assertThat(doc).isNotNull();
            assertThat(doc.getProductCode()).isEqualTo(1001L);
            assertThat(doc.getProductName()).isEqualTo("MacBook Pro");
            assertThat(doc.getPrice()).isEqualTo(2499.99);
            assertThat(doc.getStatus()).isEqualTo(ProductStatus.ACTIVE);
            assertThat(doc.getDescription()).isEqualTo("High performance laptop");
            assertThat(doc.getBrand()).isEqualTo("Apple");
            assertThat(doc.getCategoryCode()).isEqualTo(CategoryCode.LAPTOP);
        }

        @Test
        @DisplayName("Should handle null price by defaulting to 0")
        void mapNullPriceToZero() {
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
            ProductDoc doc = ProductDocMapper.mapToDoc(event);

            // Then
            assertThat(doc).isNotNull();
            assertThat(doc.getProductCode()).isEqualTo(1002L);
            assertThat(doc.getProductName()).isEqualTo("Free Product");
            assertThat(doc.getPrice()).isZero();
            assertThat(doc.getStatus()).isEqualTo(ProductStatus.INACTIVE);
        }

        @Test
        @DisplayName("Should return null when event is null")
        void mapNullEventReturnsNull() {
            // Given
            ProductCreatedEvent event = null;

            // When
            ProductDoc doc = ProductDocMapper.mapToDoc(event);

            // Then
            assertThat(doc).isNull();
        }

        @Test
        @DisplayName("Should handle all enum values for CategoryCode")
        void mapAllCategoryCodes() {
            for (CategoryCode code : CategoryCode.values()) {
                if (code == CategoryCode.UNKNOWN) continue;
                ProductCreatedEvent event = new ProductCreatedEvent(
                        2000L + code.ordinal(),
                        "Product " + code,
                        BigDecimal.valueOf(100),
                        ProductStatus.ACTIVE,
                        "Description",
                        "Brand",
                        code
                );

                ProductDoc doc = ProductDocMapper.mapToDoc(event);

                assertThat(doc.getCategoryCode()).isEqualTo(code);
            }
        }

        @Test
        @DisplayName("Should handle all ProductStatus values")
        void mapAllProductStatuses() {
            ProductCreatedEvent activeEvent = new ProductCreatedEvent(
                    3001L, "Active", BigDecimal.TEN, ProductStatus.ACTIVE,
                    "desc", "brand", CategoryCode.ELECTRONICS
            );
            ProductCreatedEvent inactiveEvent = new ProductCreatedEvent(
                    3002L, "Inactive", BigDecimal.TEN, ProductStatus.INACTIVE,
                    "desc", "brand", CategoryCode.ELECTRONICS
            );

            assertThat(ProductDocMapper.mapToDoc(activeEvent).getStatus()).isEqualTo(ProductStatus.ACTIVE);
            assertThat(ProductDocMapper.mapToDoc(inactiveEvent).getStatus()).isEqualTo(ProductStatus.INACTIVE);
        }

        @Test
        @DisplayName("Should preserve all numeric precision for price")
        void preservePricePrecision() {
            // Given
            ProductCreatedEvent event = new ProductCreatedEvent(
                    4001L,
                    "Precise Price",
                    BigDecimal.valueOf(99.9999),
                    ProductStatus.ACTIVE,
                    "desc",
                    "brand",
                    CategoryCode.ELECTRONICS
            );

            // When
            ProductDoc doc = ProductDocMapper.mapToDoc(event);

            // Then
            assertThat(doc.getPrice()).isEqualTo(99.9999);
        }
    }

    // =========================================================================
    // ProductUpdatedEvent mapping tests
    // =========================================================================

    @Nested
    @DisplayName("ProductUpdatedEvent → ProductDoc mapping")
    class UpdatedEventMappingTests {

        @Test
        @DisplayName("Should map all fields correctly from ProductUpdatedEvent")
        void mapAllFields() {
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
            ProductDoc doc = ProductDocMapper.mapToDoc(event);

            // Then
            assertThat(doc).isNotNull();
            assertThat(doc.getProductCode()).isEqualTo(2001L);
            assertThat(doc.getProductName()).isEqualTo("Galaxy S25");
            assertThat(doc.getPrice()).isEqualTo(1299.0);
            assertThat(doc.getStatus()).isEqualTo(ProductStatus.ACTIVE);
            assertThat(doc.getDescription()).isEqualTo("Samsung flagship phone");
            assertThat(doc.getBrand()).isEqualTo("Samsung");
            assertThat(doc.getCategoryCode()).isEqualTo(CategoryCode.SMARTPHONE);
        }

        @Test
        @DisplayName("Should handle null price by defaulting to 0")
        void mapNullPriceToZero() {
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
            ProductDoc doc = ProductDocMapper.mapToDoc(event);

            // Then
            assertThat(doc).isNotNull();
            assertThat(doc.getPrice()).isZero();
        }

        @Test
        @DisplayName("Should return null when event is null")
        void mapNullEventReturnsNull() {
            // Given
            ProductUpdatedEvent event = null;

            // When
            ProductDoc doc = ProductDocMapper.mapToDoc(event);

            // Then
            assertThat(doc).isNull();
        }

        @Test
        @DisplayName("Updated event mapping should produce same result as created mapping for same fields")
        void updatedMappingMatchesCreatedMapping() {
            // Given
            ProductCreatedEvent createdEvent = new ProductCreatedEvent(
                    5001L, "Product", BigDecimal.valueOf(50), ProductStatus.ACTIVE,
                    "desc", "brand", CategoryCode.BOOK
            );
            ProductUpdatedEvent updatedEvent = new ProductUpdatedEvent(
                    5001L, "Product", BigDecimal.valueOf(50), ProductStatus.ACTIVE,
                    "desc", "brand", CategoryCode.BOOK
            );

            // When
            ProductDoc fromCreated = ProductDocMapper.mapToDoc(createdEvent);
            ProductDoc fromUpdated = ProductDocMapper.mapToDoc(updatedEvent);

            // Then
            assertThat(fromCreated).usingRecursiveComparison().isEqualTo(fromUpdated);
        }
    }

}