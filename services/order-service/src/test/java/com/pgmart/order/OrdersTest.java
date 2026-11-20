package com.pgmart.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The order SQL against a real PostgreSQL. */
class OrdersTest {

    private final JdbcClient db = TestDatabase.jdbcClient();
    private final Orders orders = new Orders(db);

    @Test
    void anOrderIsSavedWithItsItemsAndFirstEvent() {
        long user = newUser();
        Orders.Order order = insert(user, OrderStatus.PLACED, "COD", "COD_DUE", "key-1");

        assertThat(order.number()).startsWith("PGM-");
        assertThat(order.items()).containsExactly(new Orders.Item(9, "Kubernetes Field Guide", 2, 69900, 119900));
        assertThat(order.events()).extracting(Orders.Event::note).containsExactly("Order placed");
        assertThat(order.address().city()).isEqualTo("Pune");
        assertThat(order.total()).isEqualTo(139800);

        assertThat(orders.byIdempotencyKey(user, "key-1")).map(Orders.Order::id).contains(order.id());
        assertThat(orders.byIdempotencyKey(user, "key-2")).isEmpty();
        assertThat(orders.lock(order.id())).map(Orders.Order::status).contains(OrderStatus.PLACED);
        assertThat(orders.byId("missing")).isEmpty();
    }

    @Test
    void statusPaymentAndStockChangesAreSaved() {
        long user = newUser();
        Orders.Order order = insert(user, OrderStatus.PAYMENT_PENDING, "RAZORPAY", "PENDING", null);

        orders.setRazorpayOrder(order.id(), "order_rzp_" + order.id());
        assertThat(orders.byRazorpayOrderId("order_rzp_" + order.id())).map(Orders.Order::id).contains(order.id());

        orders.setPaid(order.id(), "pay_1", "Payment received online");
        orders.setStatus(order.id(), OrderStatus.CANCELLED, "REFUND_PENDING", "Cancelled by you");
        orders.setPaymentId(order.id(), "pay_2");
        orders.markStockReleased(order.id());

        Orders.Order changed = orders.byId(order.id()).orElseThrow();
        assertThat(changed.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(changed.paymentStatus()).isEqualTo("REFUND_PENDING");
        assertThat(changed.razorpayPaymentId()).isEqualTo("pay_2");
        assertThat(changed.stockReleased()).isTrue();
        assertThat(changed.events()).extracting(Orders.Event::status)
                .containsExactly("PAYMENT_PENDING", "PLACED", "CANCELLED");
        assertThat(orders.refundsPending()).extracting(Orders.Order::id).contains(order.id());

        orders.setPaymentStatus(order.id(), "REFUNDED");
        assertThat(orders.refundsPending()).extracting(Orders.Order::id).doesNotContain(order.id());
    }

    @Test
    void customerAndAdminListsShowTheRightOrders() {
        long user = newUser();
        Orders.Order placed = insert(user, OrderStatus.PLACED, "COD", "COD_DUE", null);
        Orders.Order packed = insert(user, OrderStatus.PACKED, "COD", "COD_DUE", null);

        assertThat(orders.forUser(user)).extracting(Orders.Order::id).containsExactlyInAnyOrder(placed.id(), packed.id());
        assertThat(orders.forUser(user).get(0).items()).hasSize(1);
        assertThat(orders.forUser(newUser())).isEmpty();
        assertThat(orders.forAdmin("PACKED")).extracting(Orders.Order::id).contains(packed.id()).doesNotContain(placed.id());
        assertThat(orders.forAdmin(null)).extracting(Orders.Order::id).contains(placed.id(), packed.id());
    }

    @Test
    void housekeepingFindsOldUnpaidOrdersAndStockStillHeld() {
        long user = newUser();
        Orders.Order fresh = insert(user, OrderStatus.PAYMENT_PENDING, "RAZORPAY", "PENDING", null);
        Orders.Order old = insert(user, OrderStatus.PAYMENT_PENDING, "RAZORPAY", "PENDING", null);
        db.sql("UPDATE orders SET created_at = now() - interval '31 minutes' WHERE id = ?").param(old.id()).update();
        Orders.Order cancelled = insert(user, OrderStatus.CANCELLED, "COD", "NOT_PAID", null);

        assertThat(orders.unpaidOlderThan(30)).contains(old.id()).doesNotContain(fresh.id());
        assertThat(orders.cancelledWithStockHeld()).contains(cancelled.id());

        orders.markStockReleased(cancelled.id());
        assertThat(orders.cancelledWithStockHeld()).doesNotContain(cancelled.id());
    }

    @Test
    void adminNumbersCountEveryStatusAndOnlyRealSales() {
        var countsBefore = orders.countByStatus();
        long revenueBefore = orders.revenue();

        long user = newUser();
        insert(user, OrderStatus.DELIVERED, "COD", "PAID", null);
        insert(user, OrderStatus.CANCELLED, "COD", "NOT_PAID", null);

        var counts = orders.countByStatus();
        assertThat(counts).containsOnlyKeys("PAYMENT_PENDING", "PLACED", "PACKED", "OUT_FOR_DELIVERY", "DELIVERED", "CANCELLED");
        assertThat(counts.get("DELIVERED")).isEqualTo(countsBefore.get("DELIVERED") + 1);
        assertThat(orders.revenue()).isEqualTo(revenueBefore + 139800);
    }

    @Test
    void notificationsAreListedMarkedReadAndCleared() {
        long user = newUser();
        Orders.Order order = insert(user, OrderStatus.PLACED, "COD", "COD_DUE", null);

        orders.notify(user, order.id(), "Order placed", "Thank you!");
        orders.notify(user, order.id(), "Order packed", "On its way soon");
        assertThat(orders.notifications(user)).extracting(Orders.Notification::title)
                .containsExactly("Order packed", "Order placed");
        assertThat(orders.notifications(user)).noneMatch(Orders.Notification::read);

        orders.markAllRead(user);
        assertThat(orders.notifications(user)).allMatch(Orders.Notification::read);

        orders.clearNotifications(user);
        assertThat(orders.notifications(user)).isEmpty();
    }

    // ---------- test data ----------

    private Orders.Order insert(long user, OrderStatus status, String method, String paymentStatus, String key) {
        List<CatalogClient.ReservedLine> lines = List.of(
                new CatalogClient.ReservedLine(9, "Kubernetes Field Guide", 2, 69900, 119900));
        var order = new Orders.NewOrder(UUID.randomUUID().toString(), user, "Asha", "asha@example.com", status, method,
                paymentStatus, Pricing.of(lines), address(), key);
        return orders.insert(order, lines, status == OrderStatus.PLACED ? "Order placed" : status.name());
    }

    static Orders.Address address() {
        return new Orders.Address("Asha", "9876543210", "12 MG Road", "Pune", "Maharashtra", "411001");
    }

    /** Every test works on its own user, so tests never see each other's orders. */
    static long newUser() {
        return Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1;
    }
}
