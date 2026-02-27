package io.jin.web.adapter;

import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;
import io.jin.web.HttpHandler;
import io.jin.web.HttpMethod;
import io.jin.web.HttpRequest;
import io.jin.web.HttpResponse;
import io.jin.web.JinServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class UndertowJinServer implements JinServer {
    private final int port;
    private final HttpHandler handler;
    private Undertow server;

    public UndertowJinServer(int port, HttpHandler handler) {
        this.port = port;
        this.handler = handler;
    }

    @Override
    public void start() {
        this.server = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(this::handleExchange)
                .build();
        server.start();
        System.out.println("Jin running on Undertow at http://localhost:" + port);
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }

    private void handleExchange(HttpServerExchange exchange) throws IOException {
        if (exchange.isInIoThread()) {
            exchange.dispatch(() -> {
                try {
                    handleExchange(exchange);
                } catch (IOException e) {
                    sendInternalError(exchange, e);
                }
            });
            return;
        }

        HttpMethod method = HttpMethod.fromToken(exchange.getRequestMethod().toString());
        if (method == null) {
            exchange.setStatusCode(405);
            exchange.getResponseHeaders().put(new HttpString("Content-Type"), "text/plain; charset=UTF-8");
            exchange.getResponseSender().send("Method Not Allowed: " + exchange.getRequestMethod());
            return;
        }

        exchange.startBlocking();
        String body = new String(exchange.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach(header -> headers.put(header.getHeaderName().toString(), header.getFirst()));

        HttpRequest request = new HttpRequest(
                method,
                exchange.getRequestPath(),
                exchange.getQueryString().isBlank() ? null : exchange.getQueryString(),
                headers,
                body
        );
        HttpResponse response = handler.handle(request);

        exchange.setStatusCode(response.getStatus());
        response.getHeaders().forEach((name, value) -> exchange.getResponseHeaders().put(new HttpString(name), value));
        if (!exchange.getResponseHeaders().contains(new HttpString("Content-Type"))) {
            exchange.getResponseHeaders().put(new HttpString("Content-Type"), "text/plain; charset=UTF-8");
        }
        exchange.getResponseSender().send(response.getBody() == null ? "" : response.getBody());
    }

    private void sendInternalError(HttpServerExchange exchange, Exception e) {
        if (exchange.isResponseStarted()) {
            return;
        }
        exchange.setStatusCode(500);
        exchange.getResponseHeaders().put(new HttpString("Content-Type"), "text/plain; charset=UTF-8");
        exchange.getResponseSender().send("Internal Server Error: " + e.getMessage());
    }
}
