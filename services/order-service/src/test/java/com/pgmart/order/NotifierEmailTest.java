package com.pgmart.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class NotifierEmailTest {

    private final Orders orders = mock(Orders.class);
    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final Notifier notifier = new Notifier(orders, mail, "PGMart <no-reply@example.com>", "https://store.example.com");

    private final Orders.Order order = new Orders.Order("order-1", "PGM-100001", 7, "Asha", "asha@example.com",
            OrderStatus.PLACED, "COD", "COD_DUE", 139800, 239800, 0, 139800, OrdersTest.address(), null, null, false,
            null, List.of(new Orders.Item(9, "Kubernetes Field Guide", 2, 69900, 119900)), List.of());

    @Test
    void everyStatusHasAMessage() {
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(Notifier.messageFor(order, status).title()).contains("PGM-100001");
        }
        assertThat(Notifier.messageFor(order, OrderStatus.OUT_FOR_DELIVERY).body()).contains("₹1398");
    }

    @Test
    void recordSavesTheBellRow() {
        Notifier.Message message = notifier.record(order, OrderStatus.PACKED);

        verify(orders).notify(7, "order-1", message.title(), message.body());
    }

    @Test
    void theEmailListsTheItemsTotalAndAddress() {
        notifier.email(order, Notifier.messageFor(order, OrderStatus.PLACED));

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("asha@example.com");
        assertThat(sent.getValue().getSubject()).isEqualTo("PGMart: Order PGM-100001 placed");
        assertThat(sent.getValue().getText()).contains("Kubernetes Field Guide x 2", "Cash on Delivery", "Pune",
                "https://store.example.com/shop/orders");
    }

    @Test
    void aMailServerErrorDoesNotBreakTheOrder() {
        doThrow(new MailSendException("SMTP down")).when(mail).send(any(SimpleMailMessage.class));

        assertThatCode(() -> notifier.email(order, Notifier.messageFor(order, OrderStatus.PLACED)))
                .doesNotThrowAnyException();
    }
}
