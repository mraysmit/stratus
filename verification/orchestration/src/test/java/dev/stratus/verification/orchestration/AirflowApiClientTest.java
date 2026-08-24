// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Real HTTP serialization and response-parsing checks for the Airflow API boundary. The local
 * listener is a purpose-built protocol fixture; no Airflow behavior is simulated here. Product
 * compatibility is established separately by the live orchestration integration test.
 */
@Tag("unit")
final class AirflowApiClientTest {

    private static final String TOKEN = "sensitive-bearer-token";
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void authenticatesAndExercisesTheAirflowV2Protocol() throws Exception {
        var triggerBody = new AtomicReference<String>();
        var dagPolls = new AtomicInteger();
        startServer(exchange -> {
            assertEquals(null, exchange.getRequestHeaders().getFirst("Upgrade"),
                    "Airflow's HTTP/1.1 server rejects Java's clear-text h2c upgrade request");
            assertEquals(null, exchange.getRequestHeaders().getFirst("HTTP2-Settings"),
                    "Airflow requests must not carry HTTP/2 upgrade settings");
            String path = exchange.getRequestURI().getRawPath();
            if ("/auth/token".equals(path)) {
                assertEquals("POST", exchange.getRequestMethod());
                String body = readBody(exchange);
                assertTrue(body.contains("\"username\":\"admin\""));
                assertTrue(body.contains("\"password\":\"password\""));
                respond(exchange, 200, "{\"access_token\":\"" + TOKEN + "\"}");
                return;
            }
            assertEquals("Bearer " + TOKEN,
                    exchange.getRequestHeaders().getFirst("Authorization"));
            if ("/api/v2/monitor/health".equals(path)) {
                respond(exchange, 200, "{\"metadatabase\":{\"status\":\"healthy\"},"
                        + "\"scheduler\":{\"status\":\"healthy\"}}");
            } else if ("/api/v2/dags".equals(path)) {
                assertEquals("limit=100", exchange.getRequestURI().getRawQuery());
                respond(exchange, 200, "{\"dags\":["
                        + "{\"dag_id\":\"stratus_table_maintenance\",\"is_paused\":false},"
                        + "{\"dag_id\":\"stratus_bronze_to_silver\",\"is_paused\":false}]}");
            } else if (path.endsWith("/dagRuns") && "POST".equals(exchange.getRequestMethod())) {
                triggerBody.set(readBody(exchange));
                respond(exchange, 200, "{\"dag_run_id\":\"verify/run 1\",\"state\":\"queued\"}");
            } else if (path.endsWith("/dagRuns/verify%2Frun%201")) {
                String state = dagPolls.incrementAndGet() < 2 ? "running" : "success";
                respond(exchange, 200, "{\"dag_run_id\":\"verify/run 1\",\"state\":\""
                        + state + "\",\"start_date\":\"2026-08-24T01:02:03Z\","
                        + "\"end_date\":\"2026-08-24T01:02:05Z\"}");
            } else if (path.endsWith("/taskInstances")) {
                respond(exchange, 200, "{\"task_instances\":["
                        + "{\"task_id\":\"gate\",\"state\":\"success\",\"try_number\":1,"
                        + "\"start_date\":\"2026-08-24T01:02:03Z\","
                        + "\"end_date\":\"2026-08-24T01:02:04Z\"},"
                        + "{\"task_id\":\"write\",\"state\":\"success\",\"try_number\":1,"
                        + "\"start_date\":\"2026-08-24T01:02:04Z\","
                        + "\"end_date\":\"2026-08-24T01:02:05Z\"}]}");
            } else {
                respond(exchange, 404, "{\"detail\":\"unexpected route\"}");
            }
        });

        try (var client = AirflowApiClient.connect(config())) {
            var health = client.health();
            assertEquals("healthy", health.metadatabaseStatus());
            assertEquals("healthy", health.schedulerStatus());

            var dags = client.dags();
            assertEquals(2, dags.size());
            assertFalse(dags.get("stratus_table_maintenance").paused());

            var triggered = client.triggerDag(
                    "stratus_table_maintenance", "verify/run 1", Map.of("policy", "development"));
            assertEquals("queued", triggered.state());
            assertTrue(triggerBody.get().contains("\"dag_run_id\":\"verify/run 1\""));
            assertTrue(triggerBody.get().contains("\"logical_date\":null"),
                    "Airflow 3.3.1 requires the nullable logical_date property to be present");
            assertTrue(triggerBody.get().contains("\"policy\":\"development\""));

            var completed = client.awaitTerminalRun(
                    "stratus_table_maintenance", "verify/run 1",
                    Duration.ofMillis(5), Duration.ofSeconds(2));
            assertEquals("success", completed.state());
            assertTrue(completed.duration().toMillis() >= 2_000);

            var tasks = client.taskInstances("stratus_table_maintenance", "verify/run 1");
            assertEquals("success", tasks.get("gate").state());
            assertEquals(1, tasks.get("write").tryNumber());
            assertEquals(Duration.ofSeconds(1), tasks.get("write").duration());
        }
    }

    @Test
    void rejectsMalformedSuccessAndRedactsSecretsFromApiFailures() throws Exception {
        startServer(exchange -> {
            if ("/auth/token".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, "{\"access_token\":\"" + TOKEN + "\"}");
            } else {
                respond(exchange, 503, "upstream failed while token=" + TOKEN);
            }
        });

        try (var client = AirflowApiClient.connect(config())) {
            var failure = assertThrows(AirflowApiException.class, client::health);
            assertEquals(503, failure.statusCode());
            assertFalse(failure.getMessage().contains(TOKEN));
            assertFalse(failure.getMessage().contains("password"));
            assertTrue(failure.getMessage().contains("HTTP 503"));
        }

        stopServer();
        server = null;
        startServer(exchange -> respond(exchange, 200, "{\"not_access_token\":true}"));
        var malformed = assertThrows(AirflowApiException.class,
                () -> AirflowApiClient.connect(config()));
        assertTrue(malformed.getMessage().contains("access_token"));
    }

    @Test
    void timesOutWithTheLastObservedState() throws Exception {
        startServer(exchange -> {
            if ("/auth/token".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, "{\"access_token\":\"" + TOKEN + "\"}");
            } else {
                respond(exchange, 200, "{\"dag_run_id\":\"run\",\"state\":\"running\"}");
            }
        });

        try (var client = AirflowApiClient.connect(config())) {
            var failure = assertThrows(AirflowRunTimeoutException.class,
                    () -> client.awaitTerminalRun("dag", "run",
                            Duration.ofMillis(5), Duration.ofMillis(20)));
            assertEquals("running", failure.lastState());
        }
    }

    @Test
    void anonymousDevelopmentAdminUsesTheCredentialFreeTokenRoute() throws Exception {
        startServer(exchange -> {
            assertEquals("/auth/token", exchange.getRequestURI().getPath());
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals("", readBody(exchange));
            respond(exchange, 201, "{\"access_token\":\"" + TOKEN + "\"}");
        });
        Map<String, String> environment = new HashMap<>();
        environment.put("STRATUS_AIRFLOW_BASE_URL",
                "http://127.0.0.1:" + server.getAddress().getPort());
        environment.put("STRATUS_AIRFLOW_ALLOW_HTTP", "true");
        environment.put("STRATUS_AIRFLOW_ANONYMOUS_ADMIN", "true");
        environment.put("STRATUS_AIRFLOW_USERNAME", "unused");
        environment.put("STRATUS_AIRFLOW_PASSWORD", "unused");

        try (var ignored = AirflowApiClient.connect(AirflowVerifierConfig.from(environment))) {
            assertTrue(true, "the credential-free development token was accepted");
        }
    }

    private AirflowVerifierConfig config() {
        Map<String, String> environment = new HashMap<>();
        environment.put("STRATUS_AIRFLOW_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort());
        environment.put("STRATUS_AIRFLOW_ALLOW_HTTP", "true");
        environment.put("STRATUS_AIRFLOW_USERNAME", "admin");
        environment.put("STRATUS_AIRFLOW_PASSWORD", "password");
        return AirflowVerifierConfig.from(environment);
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Throwable failure) {
                byte[] body = failure.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
        });
        server.start();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
