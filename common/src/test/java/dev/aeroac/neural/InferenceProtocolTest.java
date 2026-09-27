package dev.aeroac.neural;

import dev.aeroac.neural.inference.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class InferenceProtocolTest {
    @Test void requestDeclaresTheShapeItCarries() {
        InferenceRequest request = request(3);
        assertEquals(3 * ModelFeature.FEATURE_COUNT, request.features().length);
        assertEquals(ModelFeature.FEATURE_COUNT, request.featureCount());
        assertEquals(FeatureEncoder.FEATURE_SCHEMA_VERSION, request.featureSchemaVersion());
        assertThrows(IllegalArgumentException.class, () -> new InferenceRequest(1, ModelKind.FLASH,
                ModelWindow.ATTACK, 4, 2, 1, new float[7], 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new InferenceRequest(1, ModelKind.FLASH,
                ModelWindow.ATTACK, 0, 2, 1, new float[0], 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new InferenceRequest(1, ModelKind.FLASH,
                ModelWindow.ATTACK, 1, 2, 1, null, 0, 0));
    }

    @Test void encodedBodyCarriesEveryVersionTheServiceMustCheck() {
        JsonObject body = new JsonParser().parse(InferenceJson.encode(request(2))).getAsJsonObject();
        assertEquals(InferenceRequest.PROTOCOL_VERSION, body.get("protocolVersion").getAsInt());
        assertEquals(FeatureEncoder.FEATURE_SCHEMA_VERSION, body.get("featureSchemaVersion").getAsInt());
        assertEquals("flash", body.get("model").getAsString());
        assertEquals("attack", body.get("window").getAsString());
        assertEquals(2, body.get("sequenceLength").getAsInt());
        assertEquals(ModelFeature.FEATURE_COUNT, body.get("featureCount").getAsInt());
        assertEquals(2 * ModelFeature.FEATURE_COUNT, body.getAsJsonArray("features").size());
        assertFalse(body.toString().contains("NaN"));
    }

    @Test void decodeAcceptsAWellFormedAnswerWithUnknownHeads() {
        InferenceRequest request = request(1);
        InferenceResponse response = InferenceJson.decode(reply(request.requestId(),
                "\"overall\":0.91,\"aimAssist\":0.95,\"newHeadFromANewerModel\":0.5"), request);
        assertEquals("test-v1", response.modelVersion());
        assertEquals(ModelKind.FLASH, response.model());
        assertTrue(response.calibrated());
        assertEquals(0.95, response.head("aimAssist"), 1.0E-9);
        assertEquals(0.5, response.head("newHeadFromANewerModel"), 1.0E-9);
        assertEquals(-1, response.head("absent"), 1.0E-9);
    }

    @Test void decodeRefusesEveryIncompatibleOrMalformedAnswer() {
        InferenceRequest request = request(1);
        assertThrows(IllegalArgumentException.class, () -> InferenceJson.decode("[]", request));
        assertThrows(IllegalArgumentException.class, () -> InferenceJson.decode("{}", request));
        rejects(request, "{\"protocolVersion\":2,\"featureSchemaVersion\":" + FeatureEncoder.FEATURE_SCHEMA_VERSION
                + ",\"requestId\":1,\"modelVersion\":\"v\",\"heads\":{\"overall\":0.5}}");
        rejects(request, "{\"protocolVersion\":1,\"featureSchemaVersion\":99,\"requestId\":1,\"modelVersion\":\"v\",\"heads\":{\"overall\":0.5}}");
        rejects(request, "{\"protocolVersion\":1,\"featureSchemaVersion\":" + FeatureEncoder.FEATURE_SCHEMA_VERSION
                + ",\"requestId\":777,\"modelVersion\":\"v\",\"heads\":{\"overall\":0.5}}");
        rejects(request, reply(request.requestId(), "\"overall\":1.5"));
        rejects(request, reply(request.requestId(), "\"overall\":-0.1"));
        rejects(request, reply(request.requestId(), "\"overall\":\"high\""));
        rejects(request, reply(request.requestId(), "\"aimAssist\":0.9"));
        rejects(request, "{\"protocolVersion\":1,\"featureSchemaVersion\":" + FeatureEncoder.FEATURE_SCHEMA_VERSION
                + ",\"requestId\":1,\"modelVersion\":\"v\",\"heads\":{}}");
        rejects(request, "{\"protocolVersion\":1,\"featureSchemaVersion\":" + FeatureEncoder.FEATURE_SCHEMA_VERSION
                + ",\"requestId\":1,\"heads\":{\"overall\":0.5}}");
    }

    @Test void anAnswerWithoutCalibrationIsCarriedThroughHonestly() {
        InferenceRequest request = request(1);
        InferenceResponse response = InferenceJson.decode("{\"protocolVersion\":1,\"featureSchemaVersion\":"
                + FeatureEncoder.FEATURE_SCHEMA_VERSION
                + ",\"requestId\":" + request.requestId() + ",\"modelVersion\":\"v\",\"heads\":{\"overall\":0.5}}", request);
        assertFalse(response.calibrated());
    }

    @Test void anOptionalCalibrationPriorIsCarriedAndValidated() {
        InferenceRequest request = request(1);
        String head = "{\"protocolVersion\":1,\"featureSchemaVersion\":" + FeatureEncoder.FEATURE_SCHEMA_VERSION
                + ",\"requestId\":" + request.requestId() + ",\"modelVersion\":\"v\",\"calibrated\":true,"
                + "\"heads\":{\"overall\":0.5}";
        assertTrue(Double.isNaN(InferenceJson.decode(head + "}", request).calibrationPrior()));
        assertEquals(0.2, InferenceJson.decode(head + ",\"calibrationPrior\":0.2}", request).calibrationPrior(), 0);
        assertThrows(IllegalArgumentException.class, () -> InferenceJson.decode(head + ",\"calibrationPrior\":1.5}", request));
        assertThrows(IllegalArgumentException.class, () -> InferenceJson.decode(head + ",\"calibrationPrior\":\"x\"}", request));
    }

    @Test void protocolIdentifiersAndFlagsMustNotBeCoerced() {
        String valid = reply(1, "\"overall\":0.5");
        for (String field : new String[]{"requestId", "protocolVersion", "featureSchemaVersion"}) {
            JsonObject object = JsonParser.parseString(valid).getAsJsonObject();
            object.addProperty(field, object.get(field).getAsDouble() + 0.1);
            rejects(request(1), object.toString());
            object.remove(field);
            rejects(request(1), object.toString());
        }
        rejects(request(1), valid.replace("\"calibrated\":true", "\"calibrated\":\"true\""));
        rejects(request(1), valid.replace("\"model\":\"flash\"", "\"model\":\"cascade\""));
    }

    @Test void aHealthyServiceRoundTripsAndReportsLatency() throws Exception {
        withServer(exchange -> respond(exchange, 200, reply(1, "\"overall\":0.87,\"aimAssist\":0.9")), (client, url) -> {
            InferenceResponse response = client.infer(request(2)).get(5, TimeUnit.SECONDS);
            assertEquals(0.87, response.head("overall"), 1.0E-9);
            assertEquals(1, client.health().acceptedCount());
            assertEquals(0, client.health().failedCount());
            assertTrue(client.health().averageLatencyMs() >= 0);
        });
    }

    @Test void aTimeoutIsRecordedAsServiceHealthAndNothingElse() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        withServer(exchange -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, reply(1, "\"overall\":0.5"));
        }, (client, url) -> {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                assertThrows(ExecutionException.class, () -> client.infer(request(1)).get(5, TimeUnit.SECONDS));
            });
            release.countDown();
            assertEquals(1, client.health().timedOutCount());
            assertEquals(0, client.health().acceptedCount());
        });
    }

    @Test void rateLimitAndSchemaRefusalsAreCountedApartFromTransportFailures() throws Exception {
        withServer(exchange -> respond(exchange, 429, "{}"), (client, url) -> {
            assertThrows(ExecutionException.class, () -> client.infer(request(1)).get(5, TimeUnit.SECONDS));
            assertEquals(1, client.health().rejectedCount());
            assertEquals(0, client.health().failedCount());
        });
        withServer(exchange -> respond(exchange, 503, "{}"), (client, url) -> {
            assertThrows(ExecutionException.class, () -> client.infer(request(1)).get(5, TimeUnit.SECONDS));
            assertEquals(1, client.health().failedCount());
            assertEquals(0, client.health().rejectedCount());
        });
        withServer(exchange -> respond(exchange, 200, "not json at all"), (client, url) -> {
            assertThrows(ExecutionException.class, () -> client.infer(request(1)).get(5, TimeUnit.SECONDS));
            assertEquals(1, client.health().rejectedCount());
        });
    }

    @Test void anOversizedAnswerIsCutOffInsteadOfBuffered() throws Exception {
        withServer(exchange -> respond(exchange, 200, "x".repeat(128 * 1024)), (client, url) -> {
            assertThrows(ExecutionException.class, () -> client.infer(request(1)).get(10, TimeUnit.SECONDS));
            assertEquals(1, client.health().rejectedCount());
        });
    }

    @Test void admissionShedsInsteadOfQueueingWhenTheServiceIsSaturated() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch arrived = new CountDownLatch(1);
        withServerLimited(1, 10000, exchange -> {
            arrived.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, reply(1, "\"overall\":0.5"));
        }, (client, url) -> {
            assertTrue(client.admit());
            var pending = client.infer(request(1));
            assertTrue(arrived.await(5, TimeUnit.SECONDS));
            assertFalse(client.admit(), "a second permit must not be handed out");
            assertEquals(1, client.health().shedCount());
            release.countDown();
            pending.get(5, TimeUnit.SECONDS);
            assertTrue(client.admit(), "the permit is released when the exchange completes");
        });
    }

    @Test void aClosedClientAdmitsNothingAndNeverThrowsOnThePacketPath() throws Exception {
        HttpInferenceClient client = new HttpInferenceClient("http://127.0.0.1:1/predict", 100, 4, 1);
        client.close();
        assertFalse(client.admit());
        // A shutting-down executor rejects synchronously; that must arrive as a failed future,
        // not as an exception thrown into packet processing.
        var pending = client.infer(request(1));
        assertTrue(pending.isCompletedExceptionally());
        assertThrows(ExecutionException.class, () -> pending.get(1, TimeUnit.SECONDS));
        assertEquals(1, client.health().failedCount() + client.health().rejectedCount());
    }

    private static void rejects(InferenceRequest request, String body) {
        assertThrows(IllegalArgumentException.class, () -> InferenceJson.decode(body, request),
                () -> "accepted malformed body: " + body);
    }

    private static String reply(long requestId, String heads) {
        return "{\"protocolVersion\":1,\"featureSchemaVersion\":" + FeatureEncoder.FEATURE_SCHEMA_VERSION
                + ",\"requestId\":" + requestId
                + ",\"modelVersion\":\"test-v1\",\"model\":\"flash\",\"calibrated\":true,\"heads\":{" + heads + "}}";
    }

    private static InferenceRequest request(int length) {
        return InferenceRequest.of(1, ModelKind.FLASH, ModelWindow.ATTACK,
                new float[length * ModelFeature.FEATURE_COUNT], length, 0, 0);
    }

    private interface ClientTest {
        void run(HttpInferenceClient client, String url) throws Exception;
    }

    private static void withServer(Consumer<HttpExchange> handler, ClientTest test) throws Exception {
        withServerLimited(4, handler, test);
    }

    private static void withServerLimited(int maxInFlight, Consumer<HttpExchange> handler, ClientTest test) throws Exception {
        withServerLimited(maxInFlight, 250, handler, test);
    }

    private static void withServerLimited(int maxInFlight, int timeoutMs, Consumer<HttpExchange> handler, ClientTest test) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/predict", handler::accept);
        var serverExecutor = java.util.concurrent.Executors.newFixedThreadPool(4);
        server.setExecutor(serverExecutor);
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/predict";
        HttpInferenceClient client = new HttpInferenceClient(url, timeoutMs, maxInFlight, 2);
        try {
            test.run(client, url);
        } finally {
            client.close();
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try (OutputStream output = exchange.getResponseBody()) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            output.write(bytes);
        } catch (IOException ignored) {
            // The client may already have given up; that is exactly what these tests exercise.
        }
    }
}
