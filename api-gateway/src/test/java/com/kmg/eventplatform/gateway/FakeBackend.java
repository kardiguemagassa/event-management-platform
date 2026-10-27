package com.kmg.eventplatform.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Real HTTP server (JDK built-in) answering 200 "{}", optionally after a scripted delay. */
final class FakeBackend {

    private final HttpServer server;
    private final Queue<Duration> delays = new ConcurrentLinkedQueue<>();
    private final AtomicInteger requests = new AtomicInteger();

    private FakeBackend(HttpServer server) {
        this.server = server;
    }

    static FakeBackend start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            FakeBackend backend = new FakeBackend(server);
            server.createContext("/", backend::handle);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return backend;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    void answerSlowly(Duration delay) {
        delays.add(delay);
    }

    int requests() {
        return requests.get();
    }

    void reset() {
        delays.clear();
        requests.set(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        Duration delay = delays.poll();
        if (delay != null) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        exchange.getRequestBody().readAllBytes();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
