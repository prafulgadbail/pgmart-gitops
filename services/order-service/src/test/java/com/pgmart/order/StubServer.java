package com.pgmart.order;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

/**
 * A small HTTP server on a free local port that stands in for catalog-service or the Razorpay API,
 * answers with the status and JSON body a test sets, and records what it received.
 */
final class StubServer implements AutoCloseable {

    record Request(String method, String path, String body, String internalToken, String authorization) {
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "";

    StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("X-Internal-Token"),
                    exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        server.start();
    }

    StubServer respond(int status, String body) {
        this.status = status;
        this.body = body;
        return this;
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    List<Request> requests() {
        return requests;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
