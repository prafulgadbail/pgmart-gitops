package com.pgmart.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AdminOrderControllerTest {

    private static final String AUTH = "Bearer admin";

    private final Orders orders = mock(Orders.class);
    private final OrderService service = mock(OrderService.class);
    private final Auth auth = mock(Auth.class);
    private final AdminOrderController controller = new AdminOrderController(orders, service, auth);

    @Test
    void ordersAreListedAllOrByStatus() {
        controller.list(AUTH, null);
        controller.list(AUTH, OrderStatus.PACKED);

        verify(orders).forAdmin(null);
        verify(orders).forAdmin("PACKED");
        verify(auth, times(2)).admin(AUTH);
    }

    @Test
    void statsLeaveOutOrdersStillWaitingForPayment() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("PAYMENT_PENDING", 2L);
        counts.put("PLACED", 3L);
        counts.put("DELIVERED", 5L);
        when(orders.countByStatus()).thenReturn(counts);
        when(orders.revenue()).thenReturn(400000L);

        AdminOrderController.Stats stats = controller.stats(AUTH);

        assertThat(stats.orders()).isEqualTo(8);
        assertThat(stats.revenue()).isEqualTo(400000);
        assertThat(stats.byStatus()).isEqualTo(counts);
    }

    @Test
    void aStatusChangeGoesToTheService() {
        AuthUser admin = new AuthUser(1, "admin@example.com", "Admin", "ADMIN");
        when(auth.admin(AUTH)).thenReturn(admin);

        controller.move(AUTH, "order-1", new AdminOrderController.StatusRequest(OrderStatus.PACKED));

        verify(service).adminMove(admin, "order-1", OrderStatus.PACKED);
    }
}
