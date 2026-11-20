package com.pgmart.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

class InternalStockControllerTest {

    private final Stock stock = mock(Stock.class);
    private final Auth auth = mock(Auth.class);
    private final InternalStockController controller = new InternalStockController(stock, auth);

    @Test
    void orderServiceReservesAndReleasesWithTheSharedToken() {
        List<Stock.Line> items = List.of(new Stock.Line(9, 2));
        List<Stock.ReservedLine> reserved = List.of(new Stock.ReservedLine(9, "Kubernetes Field Guide", 2, 69900, 119900));
        when(stock.reserve("order-1", items)).thenReturn(reserved);

        assertThat(controller.reserve("token", new InternalStockController.ReserveRequest("order-1", items)))
                .isEqualTo(reserved);
        controller.release("token", new InternalStockController.ReleaseRequest("order-1"));

        verify(auth, times(2)).internal("token");
        verify(stock).release("order-1");
    }

    @Test
    void aCallWithoutTheTokenTouchesNoStock() {
        doThrow(ApiException.forbidden()).when(auth).internal(null);

        assertThatThrownBy(() -> controller.release(null, new InternalStockController.ReleaseRequest("order-1")))
                .isInstanceOf(ApiException.class);
        verify(stock, never()).release(any());
    }
}
