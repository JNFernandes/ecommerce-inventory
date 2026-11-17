package com.ecommerce.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecommerce.inventory.application.port.ProductCatalogReader;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductQueryServiceTest {

    private static final ProductView MOUSE = new ProductView("SKU-001", "Wireless Mouse", 10);
    private static final ProductView KEYBOARD = new ProductView("SKU-002", "Mechanical Keyboard", 25);

    @Mock
    private ProductCatalogReader catalog;

    private ProductQueryService service;

    @BeforeEach
    void setUp() {
        service = new ProductQueryService(catalog);
    }

    @Test
    @DisplayName("should return the page and total from the catalog")
    void shouldReturnPageAndTotal() {
        when(catalog.findPage(50, 0)).thenReturn(List.of(MOUSE, KEYBOARD));
        when(catalog.count()).thenReturn(5L);

        ProductPage page = service.list(50, 0);

        assertThat(page).isEqualTo(new ProductPage(List.of(MOUSE, KEYBOARD), 5, 50, 0));
    }

    @Test
    @DisplayName("should pass limit and offset unchanged to the catalog")
    void shouldPassLimitAndOffset() {
        when(catalog.findPage(20, 40)).thenReturn(List.of());

        ProductPage page = service.list(20, 40);

        verify(catalog).findPage(20, 40);
        assertThat(page.limit()).isEqualTo(20);
        assertThat(page.offset()).isEqualTo(40);
    }

    @Test
    @DisplayName("should return an empty page with the total when the offset is past the end")
    void shouldReturnEmptyPagePastTheEnd() {
        when(catalog.findPage(50, 10)).thenReturn(List.of());
        when(catalog.count()).thenReturn(5L);

        ProductPage page = service.list(50, 10);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isEqualTo(5);
    }

    @ParameterizedTest(name = "limit {0}, offset {1}")
    @CsvSource({"0, 0", "201, 0", "-1, 0", "50, -1"})
    @DisplayName("should reject a limit outside 1..200 or a negative offset without reading the catalog")
    void shouldRejectOutOfRangeArguments(int limit, int offset) {
        assertThatThrownBy(() -> service.list(limit, offset)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(catalog);
    }
}
