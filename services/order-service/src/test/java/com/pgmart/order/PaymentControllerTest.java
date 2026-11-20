package com.pgmart.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import tools.jackson.databind.json.JsonMapper;

class PaymentControllerTest {

    private static final String CAPTURED = """
            {"event": "payment.captured",
             "payload": {"payment": {"entity": {"id": "pay_1", "order_id": "order_rzp"}}}}""";

    private final Razorpay razorpay = mock(Razorpay.class);
    private final OrderService service = mock(OrderService.class);
    private final PaymentController controller = new PaymentController(razorpay, service, JsonMapper.builder().build());

    @Test
    void optionsSayWhetherOnlinePaymentIsOn() {
        when(razorpay.enabled()).thenReturn(true);

        assertThat(controller.options()).containsEntry("cod", true).containsEntry("online", true);
    }

    @Test
    void aSignedCapturedPaymentMarksTheOrderPaid() throws Exception {
        when(razorpay.webhookSignatureValid(CAPTURED, "good")).thenReturn(true);

        assertThat(controller.webhook("good", CAPTURED).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).markPaid("order_rzp", "pay_1", "webhook");
    }

    @Test
    void aWebhookWithABadSignatureIsRefused() throws Exception {
        assertThat(controller.webhook("bad", CAPTURED).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).markPaid(any(), any(), any());
    }

    @Test
    void aFailedPaymentChangesNothing() throws Exception {
        String failed = "{\"event\": \"payment.failed\", \"payload\": {}}";
        when(razorpay.webhookSignatureValid(failed, "good")).thenReturn(true);

        assertThat(controller.webhook("good", failed).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service, never()).markPaid(any(), any(), any());
    }
}
