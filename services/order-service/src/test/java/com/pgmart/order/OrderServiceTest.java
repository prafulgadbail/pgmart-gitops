package com.pgmart.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checkout and the order life cycle with the real SQL and transactions on PostgreSQL.
 * Only the other systems are stand-ins: catalog-service, Razorpay and the mail server.
 */
class OrderServiceTest {

    private final DataSource dataSource = TestDatabase.dataSource();
    private final JdbcClient db = JdbcClient.create(dataSource);
    private final Orders orders = new Orders(db);
    private final CatalogClient catalog = mock(CatalogClient.class);
    private final Razorpay razorpay = mock(Razorpay.class);
    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final Notifier notifier = new Notifier(orders, mail, "PGMart <no-reply@example.com>", "https://store.example.com");
    private final OrderService service = new OrderService(orders, catalog, razorpay, notifier,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    private final Housekeeping housekeeping = new Housekeeping(orders, service);

    private final AuthUser asha = new AuthUser(OrdersTest.newUser(), "asha@example.com", "Asha", "CUSTOMER");
    private final AuthUser admin = new AuthUser(1, "admin@example.com", "Admin", "ADMIN");

    @BeforeEach
    void otherSystemsAnswer() {
        when(catalog.reserve(anyString(), anyList())).thenReturn(List.of(
                new CatalogClient.ReservedLine(9, "Kubernetes Field Guide", 2, 69900, 119900)));
        when(catalog.release(anyString())).thenReturn(true);
        when(razorpay.keyId()).thenReturn("rzp_test_key");
    }

    // ---------- Cash on Delivery ----------

    @Test
    void aCashOnDeliveryOrderIsPlacedNotifiedAndEmailed() {
        OrderService.Placed placed = service.place(asha, checkout("COD"), null);

        assertThat(placed.order().status()).isEqualTo(OrderStatus.PLACED);
        assertThat(placed.order().paymentStatus()).isEqualTo("COD_DUE");
        assertThat(placed.order().total()).isEqualTo(139800);
        assertThat(placed.payment()).isNull();
        assertThat(orders.notifications(asha.id())).extracting(Orders.Notification::title)
                .containsExactly("Order " + placed.order().number() + " placed");
        verify(mail).send(any(SimpleMailMessage.class));
    }

    @Test
    void aDoubleClickPlacesOneOrder() {
        OrderService.Placed first = service.place(asha, checkout("COD"), "checkout-1");
        OrderService.Placed second = service.place(asha, checkout("COD"), "checkout-1");

        assertThat(second.order().id()).isEqualTo(first.order().id());
        verify(catalog, times(1)).reserve(anyString(), anyList());
    }

    @Test
    void ifTheOrderCannotBeSavedTheStockIsGivenBack() {
        var noName = new OrderService.Checkout(List.of(new CatalogClient.Line(9, 2)),
                new Orders.Address(null, "9876543210", "12 MG Road", "Pune", "Maharashtra", "411001"), "COD");

        assertThatThrownBy(() -> service.place(asha, noName, null)).isInstanceOf(RuntimeException.class);
        verify(catalog).release(anyString());
        assertThat(orders.forUser(asha.id())).isEmpty();
    }

    @Test
    void theAdminMovesAnOrderToDeliveredAndCashIsCollected() {
        String id = service.place(asha, checkout("COD"), null).order().id();

        service.adminMove(admin, id, OrderStatus.PACKED);
        service.adminMove(admin, id, OrderStatus.OUT_FOR_DELIVERY);
        assertCode("TOO_LATE", () -> service.customerCancel(asha, id));
        assertCode("BAD_STATUS", () -> service.adminMove(admin, id, OrderStatus.PACKED));
        Orders.Order delivered = service.adminMove(admin, id, OrderStatus.DELIVERED);

        assertThat(delivered.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(delivered.paymentStatus()).isEqualTo("PAID");
        assertThat(delivered.events()).extracting(Orders.Event::note).last().isEqualTo("Delivered, cash collected");
        assertCode("BAD_STATUS", () -> service.adminMove(admin, id, OrderStatus.CANCELLED));
        verify(mail, times(4)).send(any(SimpleMailMessage.class));
    }

    @Test
    void unknownOrdersAreNotFound() {
        assertCode("NOT_FOUND", () -> service.adminMove(admin, "missing", OrderStatus.PACKED));
        assertCode("NOT_FOUND", () -> service.adminMove(admin, "missing", OrderStatus.CANCELLED));
        assertCode("NOT_FOUND", () -> service.ownOrder(asha, "missing"));
    }

    @Test
    void aCustomerCancelsAndTheStockGoesBack() {
        String id = service.place(asha, checkout("COD"), null).order().id();

        assertCode("NOT_FOUND", () -> service.customerCancel(new AuthUser(asha.id() + 1, "x@y.com", "X", "CUSTOMER"), id));
        Orders.Order cancelled = service.customerCancel(asha, id);

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.paymentStatus()).isEqualTo("NOT_PAID");
        assertThat(cancelled.stockReleased()).isTrue();
        verify(catalog).release(id);
        assertThat(service.cancel(id, "again", true).status()).isEqualTo(OrderStatus.CANCELLED);
        assertCode("NOT_PENDING", () -> service.paymentFor(asha, id));
    }

    @Test
    void theStoreCancelsAPlacedOrder() {
        String id = service.place(asha, checkout("COD"), null).order().id();

        assertThat(service.adminMove(admin, id, OrderStatus.CANCELLED).status()).isEqualTo(OrderStatus.CANCELLED);
    }

    // ---------- online payment ----------

    @Test
    void onlinePaymentNeedsRazorpayKeys() {
        assertThatThrownBy(() -> service.place(asha, checkout("RAZORPAY"), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(catalog, never()).reserve(anyString(), anyList());
    }

    @Test
    void anOnlineOrderIsPlacedWhenThePaymentIsConfirmed() {
        String rzpOrder = razorpayOnline();
        OrderService.Placed placed = service.place(asha, checkout("RAZORPAY"), null);
        String id = placed.order().id();

        assertThat(placed.order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(placed.payment()).isEqualTo(new OrderService.PaymentStart("rzp_test_key", rzpOrder, 139800));
        assertThat(service.paymentFor(asha, id)).isEqualTo(placed.payment());
        assertThat(orders.notifications(asha.id())).isEmpty();

        assertCode("PAYMENT_INVALID", () -> service.confirmPayment(asha, id, "order_other", "pay_1", "sig"));
        assertCode("PAYMENT_INVALID", () -> service.confirmPayment(asha, id, rzpOrder, "pay_1", "bad-sig"));

        when(razorpay.paymentSignatureValid(rzpOrder, "pay_1", "sig")).thenReturn(true);
        Orders.Order paid = service.confirmPayment(asha, id, rzpOrder, "pay_1", "sig");
        assertThat(paid.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(paid.paymentStatus()).isEqualTo("PAID");
        assertThat(paid.razorpayPaymentId()).isEqualTo("pay_1");

        service.markPaid(rzpOrder, "pay_1", "webhook");
        assertThat(orders.byId(id).orElseThrow().events()).filteredOn(e -> e.status().equals("PLACED")).hasSize(1);
        verify(mail, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void cancellingAPaidOrderRefundsIt() {
        String id = paidOnlineOrder("pay_refund");
        when(razorpay.refund("pay_refund", 139800)).thenReturn(true);

        Orders.Order cancelled = service.customerCancel(asha, id);

        assertThat(cancelled.paymentStatus()).isEqualTo("REFUNDED");
        assertThat(orders.notifications(asha.id()).get(0).body()).contains("refund");
    }

    @Test
    void aFailedRefundIsRetriedByHousekeeping() {
        String id = paidOnlineOrder("pay_retry");

        assertThat(service.customerCancel(asha, id).paymentStatus()).isEqualTo("REFUND_PENDING");

        when(razorpay.refund("pay_retry", 139800)).thenReturn(true);
        housekeeping.run();
        assertThat(orders.byId(id).orElseThrow().paymentStatus()).isEqualTo("REFUNDED");
    }

    @Test
    void aPaymentAfterTheOrderExpiredIsQueuedForRefund() {
        String rzpOrder = razorpayOnline();
        String id = service.place(asha, checkout("RAZORPAY"), null).order().id();
        service.customerCancel(asha, id);

        service.markPaid(rzpOrder, "pay_late", "webhook");
        service.markPaid("order_unknown", "pay_x", "webhook");

        Orders.Order order = orders.byId(id).orElseThrow();
        assertThat(order.paymentStatus()).isEqualTo("REFUND_PENDING");
        assertThat(order.razorpayPaymentId()).isEqualTo("pay_late");
    }

    @Test
    void ifThePaymentCannotStartTheOrderIsCancelled() {
        when(razorpay.enabled()).thenReturn(true);
        when(razorpay.createOrder(anyLong(), anyString(), anyString()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_GATEWAY", "Not available"));

        assertCode("PAYMENT_GATEWAY", () -> service.place(asha, checkout("RAZORPAY"), null));

        Orders.Order order = orders.forUser(asha.id()).get(0);
        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.paymentStatus()).isEqualTo("NOT_PAID");
        verify(catalog).release(order.id());
    }

    // ---------- housekeeping ----------

    @Test
    void housekeepingCancelsUnpaidOrdersAndRetriesStockReleases() {
        razorpayOnline();
        String unpaid = service.place(asha, checkout("RAZORPAY"), null).order().id();
        db.sql("UPDATE orders SET created_at = now() - interval '31 minutes' WHERE id = ?").param(unpaid).update();

        when(catalog.release(anyString())).thenReturn(false);
        String cod = service.place(asha, checkout("COD"), null).order().id();
        assertThat(service.customerCancel(asha, cod).stockReleased()).isFalse();

        when(catalog.release(anyString())).thenReturn(true);
        housekeeping.run();

        assertThat(orders.byId(unpaid).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(orders.byId(cod).orElseThrow().stockReleased()).isTrue();
        verify(catalog, atLeastOnce()).release(eq(unpaid));
    }

    // ---------- helpers ----------

    private String razorpayOnline() {
        String rzpOrder = "order_" + UUID.randomUUID();
        when(razorpay.enabled()).thenReturn(true);
        when(razorpay.createOrder(anyLong(), anyString(), anyString())).thenReturn(rzpOrder);
        return rzpOrder;
    }

    private String paidOnlineOrder(String paymentId) {
        String rzpOrder = razorpayOnline();
        String id = service.place(asha, checkout("RAZORPAY"), null).order().id();
        when(razorpay.paymentSignatureValid(rzpOrder, paymentId, "sig")).thenReturn(true);
        service.confirmPayment(asha, id, rzpOrder, paymentId, "sig");
        return id;
    }

    private static OrderService.Checkout checkout(String paymentMethod) {
        return new OrderService.Checkout(List.of(new CatalogClient.Line(9, 2)), OrdersTest.address(), paymentMethod);
    }

    private static void assertCode(String code, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
