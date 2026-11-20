package com.pgmart.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class OrderControllerTest {

    private static final String AUTH = "Bearer token";

    private final OrderService service = mock(OrderService.class);
    private final Orders orders = mock(Orders.class);
    private final Auth auth = mock(Auth.class);
    private final OrderController controller = new OrderController(service, orders, auth);

    private final AuthUser asha = new AuthUser(7, "asha@example.com", "Asha", "CUSTOMER");
    private final OrderController.CheckoutRequest checkout = new OrderController.CheckoutRequest(
            List.of(new OrderController.ItemRequest(9, 2)),
            new OrderController.AddressRequest(" Asha ", "9876543210", " 12 MG Road ", " Pune ", " Maharashtra ", "411001"),
            "COD");

    @BeforeEach
    void loggedIn() {
        when(auth.customer(AUTH)).thenReturn(asha);
    }

    @Test
    void checkoutPassesTrimmedAddressAndItemsToTheService() {
        var placed = new OrderService.Placed(null, null);
        when(service.place(eq(asha), any(), eq("key-1"))).thenReturn(placed);

        var response = controller.place(AUTH, "key-1", checkout);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(placed);
        ArgumentCaptor<OrderService.Checkout> sent = ArgumentCaptor.forClass(OrderService.Checkout.class);
        verify(service).place(eq(asha), sent.capture(), eq("key-1"));
        assertThat(sent.getValue().items()).containsExactly(new CatalogClient.Line(9, 2));
        assertThat(sent.getValue().address()).isEqualTo(OrdersTest.address());
        assertThat(sent.getValue().paymentMethod()).isEqualTo("COD");
    }

    @Test
    void aBadIdempotencyKeyIsRefused() {
        assertThatThrownBy(() -> controller.place(AUTH, " ", checkout)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.place(AUTH, "k".repeat(65), checkout)).isInstanceOf(ApiException.class);
        verify(service, never()).place(any(), any(), any());
    }

    @Test
    void theOtherEndpointsWorkOnTheLoggedInCustomer() {
        controller.mine(AUTH);
        controller.one(AUTH, "order-1");
        controller.cancel(AUTH, "order-1");
        controller.payment(AUTH, "order-1");
        controller.confirmPayment(AUTH, "order-1", new OrderController.PaymentConfirmRequest("order_rzp", "pay_1", "sig"));

        verify(orders).forUser(7);
        verify(service).ownOrder(asha, "order-1");
        verify(service).customerCancel(asha, "order-1");
        verify(service).paymentFor(asha, "order-1");
        verify(service).confirmPayment(asha, "order-1", "order_rzp", "pay_1", "sig");
    }
}
