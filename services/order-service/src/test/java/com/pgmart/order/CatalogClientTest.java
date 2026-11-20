package com.pgmart.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** The calls to catalog-service, against a stub HTTP server. */
class CatalogClientTest {

    private StubServer catalogService;
    private CatalogClient client;

    @BeforeEach
    void start() throws Exception {
        catalogService = new StubServer();
        client = new CatalogClient(catalogService.url(), "internal-token");
    }

    @AfterEach
    void stop() {
        catalogService.close();
    }

    @Test
    void reserveSendsTheOrderWithTheInternalTokenAndReadsThePrices() {
        catalogService.respond(200, """
                [{"bookId": 9, "title": "Kubernetes Field Guide", "qty": 2, "price": 69900, "mrp": 119900}]""");

        List<CatalogClient.ReservedLine> lines = client.reserve("order-1", List.of(new CatalogClient.Line(9, 2)));

        assertThat(lines).containsExactly(new CatalogClient.ReservedLine(9, "Kubernetes Field Guide", 2, 69900, 119900));
        StubServer.Request request = catalogService.requests().get(0);
        assertThat(request.path()).isEqualTo("/internal/stock/reserve");
        assertThat(request.internalToken()).isEqualTo("internal-token");
        assertThat(request.body()).contains("\"orderId\":\"order-1\"");
    }

    @Test
    void anOutOfStockAnswerReachesTheShopperAsIs() {
        catalogService.respond(409, """
                {"code": "OUT_OF_STOCK", "message": "Only 1 left of Kubernetes Field Guide"}""");

        assertThatThrownBy(() -> client.reserve("order-1", List.of(new CatalogClient.Line(9, 2))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("OUT_OF_STOCK");
                    assertThat(e.getMessage()).isEqualTo("Only 1 left of Kubernetes Field Guide");
                });
    }

    @Test
    void aCatalogErrorWithoutABodyStillHasAMessage() {
        catalogService.respond(400, "");

        assertThatThrownBy(() -> client.reserve("order-1", List.of(new CatalogClient.Line(9, 20))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("STOCK");
                });
    }

    @Test
    void anEmptyAnswerMeansNoLines() {
        catalogService.respond(200, "");

        assertThat(client.reserve("order-1", List.of(new CatalogClient.Line(9, 1)))).isEmpty();
    }

    @Test
    void catalogServiceDownIsAServiceUnavailableError() {
        catalogService.respond(500, "{}");
        assertThatThrownBy(() -> client.reserve("order-1", List.of(new CatalogClient.Line(9, 1))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CATALOG_DOWN"));

        CatalogClient unreachable = new CatalogClient("http://localhost:1", "internal-token");
        assertThatThrownBy(() -> unreachable.reserve("order-1", List.of(new CatalogClient.Line(9, 1))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CATALOG_DOWN"));
    }

    @Test
    void releaseSaysWhetherCatalogServiceGotTheCall() {
        catalogService.respond(204, "");
        assertThat(client.release("order-1")).isTrue();
        assertThat(catalogService.requests().get(0).path()).isEqualTo("/internal/stock/release");

        catalogService.respond(500, "{}");
        assertThat(client.release("order-1")).isFalse();
    }
}
