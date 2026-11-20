package com.pgmart.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Razorpay REST calls, against a stub HTTP server instead of api.razorpay.com. */
class RazorpayApiTest {

    private StubServer razorpayApi;
    private Razorpay razorpay;

    @BeforeEach
    void start() throws Exception {
        razorpayApi = new StubServer();
        razorpay = new Razorpay("rzp_test_key", "test_secret", "hook_secret", razorpayApi.url());
    }

    @AfterEach
    void stop() {
        razorpayApi.close();
    }

    @Test
    void createOrderSendsTheAmountAndReturnsTheRazorpayOrderId() {
        razorpayApi.respond(200, "{\"id\": \"order_ABC\"}");

        assertThat(razorpay.createOrder(79900, "PGM-100001", "order-1")).isEqualTo("order_ABC");
        StubServer.Request request = razorpayApi.requests().get(0);
        assertThat(request.path()).isEqualTo("/v1/orders");
        assertThat(request.authorization()).startsWith("Basic ");
        assertThat(request.body()).contains("\"amount\":79900", "\"currency\":\"INR\"");
        assertThat(razorpay.keyId()).isEqualTo("rzp_test_key");
    }

    @Test
    void aGatewayErrorAsksForCashOnDelivery() {
        razorpayApi.respond(500, "{}");

        assertThatThrownBy(() -> razorpay.createOrder(79900, "PGM-100001", "order-1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("PAYMENT_GATEWAY"));
    }

    @Test
    void refundSaysWhetherRazorpayAcceptedIt() {
        razorpayApi.respond(200, "{\"id\": \"rfnd_1\"}");
        assertThat(razorpay.refund("pay_XYZ", 79900)).isTrue();
        assertThat(razorpayApi.requests().get(0).path()).isEqualTo("/v1/payments/pay_XYZ/refund");

        razorpayApi.respond(500, "{}");
        assertThat(razorpay.refund("pay_XYZ", 79900)).isFalse();
    }

    @Test
    void signaturesAreCheckedOnlyWithKeysAndASignature() {
        Razorpay codOnly = new Razorpay("", "", "", razorpayApi.url());

        assertThat(codOnly.paymentSignatureValid("order_ABC", "pay_XYZ", "anything")).isFalse();
        assertThat(codOnly.webhookSignatureValid("{}", "anything")).isFalse();
        assertThat(razorpay.paymentSignatureValid("order_ABC", "pay_XYZ", null)).isFalse();
        assertThat(razorpay.webhookSignatureValid("{}", null)).isFalse();
    }
}
