package com.kmg.eventplatform.registration.infrastructure.client;

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

/**
 * Real HTTP server (JDK built-in) answering scripted responses, then 200 "{}" once the script is exhausted.
 */
final class FakeDownstream {

    private record Answer(int status, String body, Duration delay) {
    }

    private final HttpServer server;
    private final Queue<Answer> script = new ConcurrentLinkedQueue<>();
    private final AtomicInteger requests = new AtomicInteger();

    private FakeDownstream(HttpServer server) {
        this.server = server;
    }

    static FakeDownstream start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            FakeDownstream fake = new FakeDownstream(server);
            server.createContext("/", fake::handle);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    void answer(int status, String body) {
        script.add(new Answer(status, body, Duration.ZERO));
    }

    void answerSlowly(Duration delay) {
        script.add(new Answer(200, "{}", delay));
    }

    int requests() {
        return requests.get();
    }

    void reset() {
        script.clear();
        requests.set(0);
    }

    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        Answer answer = script.poll();
        if (answer == null) {
            answer = new Answer(200, "{}", Duration.ZERO);
        }
        try {
            Thread.sleep(answer.delay());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type",
                answer.status() >= 400 ? "application/problem+json" : "application/json");
        exchange.sendResponseHeaders(answer.status(), body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
