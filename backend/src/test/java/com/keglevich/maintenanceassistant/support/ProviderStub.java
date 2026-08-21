package com.keglevich.maintenanceassistant.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A loopback HTTP server that answers like an OpenAI-compatible provider, with canned responses.
 *
 * <p><b>Why a real socket rather than {@code MockRestServiceServer}.</b> That binds to an injected
 * {@code RestClient.Builder}, and neither provider client takes one: each calls the static
 * {@code RestClient.builder()} inside its own constructor, deliberately, so that it inherits no
 * interceptor or converter added for the application's own HTTP calls. There is therefore no
 * interception point, and {@code @RestClientTest} has nothing to bind to. Pointing the client's
 * configured {@code baseUrl} at a socket is the seam the production wiring actually leaves open.
 *
 * <p>It buys something the mock server could not, which matters for what these tests exist to pin:
 * the request is really serialised, really sent, and the response really goes through Boot's
 * message converters. So the Jackson-3 binding claim — that the typed records bind under the
 * converter generation Boot 4.1 defaults to, while Jackson 2 is still on the classpath — is
 * genuinely exercised rather than assumed.
 *
 * <p><b>No assertions live here.</b> It records what arrived and serves what was queued; what any
 * of that means belongs to the test.
 *
 * <p>Not thread-safe across tests by design — one stub per test, closed in the same test.
 */
public final class ProviderStub implements AutoCloseable {

    private final HttpServer server;
    private final Deque<Canned> responses = new ArrayDeque<>();
    private final List<RecordedRequest> received = new CopyOnWriteArrayList<>();

    /** One request as it arrived: enough to assert what the client built, and nothing more. */
    public record RecordedRequest(String method, String path, String body, Map<String, String> headers) {
    }

    private record Canned(int status, String contentType, String body) {
    }

    private ProviderStub(HttpServer server) {
        this.server = server;
    }

    /** Starts on an ephemeral loopback port. */
    public static ProviderStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ProviderStub stub = new ProviderStub(server);
            server.createContext("/", stub::handle);
            server.setExecutor(null);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The value to configure as the provider's base URL — {@code /v1}, as the real one is. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** Queues one JSON response. Responses are served in the order they were queued. */
    public ProviderStub enqueueJson(int status, String body) {
        responses.add(new Canned(status, "application/json", body));
        return this;
    }

    /** Queues a response that is not JSON — for the paths where the provider answers with prose. */
    public ProviderStub enqueue(int status, String contentType, String body) {
        responses.add(new Canned(status, contentType, body));
        return this;
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(received);
    }

    public RecordedRequest lastRequest() {
        if (received.isEmpty()) {
            throw new IllegalStateException("no request reached the stub");
        }
        return received.get(received.size() - 1);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    body,
                    firstValueOfEachHeader(exchange)));

            // An unqueued request is a test that expected fewer calls than the client made. 599 is
            // outside anything the client maps, so it surfaces as an unexpected failure rather than
            // quietly feeding a retry loop the transient error it was waiting for.
            Canned canned = responses.isEmpty()
                    ? new Canned(599, "text/plain", "no response queued for this request")
                    : responses.removeFirst();

            byte[] payload = canned.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", canned.contentType());
            exchange.sendResponseHeaders(canned.status(), payload.length);
            exchange.getResponseBody().write(payload);
        }
    }

    private static Map<String, String> firstValueOfEachHeader(HttpExchange exchange) {
        return exchange.getRequestHeaders().entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(java.util.stream.Collectors.toMap(
                        entry -> entry.getKey().toLowerCase(java.util.Locale.ROOT),
                        entry -> entry.getValue().get(0),
                        (first, second) -> first));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
