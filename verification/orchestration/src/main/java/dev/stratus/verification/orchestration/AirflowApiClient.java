// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Narrow Airflow 3 REST API client used by the executable orchestration proof. Every operation
 * emits diagnostic timing while error text deliberately excludes response bodies and credentials.
 */
public final class AirflowApiClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AirflowApiClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Set<String> TERMINAL_STATES = Set.of("success", "failed");

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI baseUri;
    private final String bearerToken;

    private AirflowApiClient(HttpClient http, ObjectMapper json, URI baseUri, String bearerToken) {
        this.http = http;
        this.json = json;
        this.baseUri = baseUri;
        this.bearerToken = bearerToken;
    }

    public static AirflowApiClient connect(AirflowVerifierConfig config) {
        // Java otherwise attempts a clear-text h2c upgrade. Airflow 3.3.1's Uvicorn listener
        // rejects a POST carrying that upgrade as "Invalid HTTP request received" before the
        // request reaches FastAPI, while ordinary HTTP/1.1 requests are accepted.
        var http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
        var json = new ObjectMapper();
        long started = System.nanoTime();
        HttpRequest request;
        if (config.anonymousAdmin()) {
            request = HttpRequest.newBuilder(config.baseUri().resolve("/auth/token"))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
        } else {
            final String body;
            try {
                body = json.writeValueAsString(Map.of(
                        "username", config.username(), "password", config.password()));
            } catch (JsonProcessingException failure) {
                throw new AirflowApiException(
                        "Cannot serialize Airflow authentication request", failure);
            }
            request = HttpRequest.newBuilder(config.baseUri().resolve("/auth/token"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
        }
        HttpResponse<String> response = send(http, request, "authenticate");
        requireSuccess(response, "authenticate");
        JsonNode root = parse(json, response.body(), "authentication response");
        JsonNode tokenNode = root.get("access_token");
        if (tokenNode == null || !tokenNode.isTextual() || tokenNode.asText().isBlank()) {
            throw new AirflowApiException(
                    "Airflow authentication response has no textual access_token", response.statusCode());
        }
        LOG.info("event=airflow_api_authenticated endpoint={} mode={} elapsedMs={}",
                config.baseUri(), config.anonymousAdmin() ? "anonymous-development-admin" : "credentials",
                elapsedMs(started));
        return new AirflowApiClient(http, json, config.baseUri(), tokenNode.asText());
    }

    public AirflowHealth health() {
        JsonNode root = get("/api/v2/monitor/health", "health");
        return new AirflowHealth(
                requiredText(root.path("metadatabase"), "status", "health.metadatabase.status"),
                requiredText(root.path("scheduler"), "status", "health.scheduler.status"));
    }

    public Map<String, AirflowDag> dags() {
        JsonNode root = get("/api/v2/dags?limit=100", "list_dags");
        JsonNode values = root.get("dags");
        if (values == null || !values.isArray()) {
            throw new AirflowApiException("Airflow DAG response has no dags array", 200);
        }
        Map<String, AirflowDag> dags = new LinkedHashMap<>();
        for (JsonNode value : values) {
            String id = requiredText(value, "dag_id", "dags[].dag_id");
            JsonNode paused = value.get("is_paused");
            if (paused == null || !paused.isBoolean()) {
                throw new AirflowApiException(
                        "Airflow DAG response has no boolean is_paused for dagId=" + id, 200);
            }
            dags.put(id, new AirflowDag(id, paused.asBoolean()));
        }
        return Map.copyOf(dags);
    }

    public AirflowDagRun triggerDag(String dagId, String runId, Map<String, ?> configuration) {
        long started = System.nanoTime();
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("dag_run_id", runId);
        // Airflow 3.3.1's TriggerDAGRunPostBody requires this property even though null asks the
        // server to assign the manual run's logical date. Omitting it returns HTTP 400.
        requestBody.put("logical_date", null);
        requestBody.put("conf", configuration);
        JsonNode root = sendJson("POST", dagRunsPath(dagId), requestBody, "trigger_dag");
        AirflowDagRun run = parseRun(root);
        LOG.info("event=airflow_dag_triggered dagId={} runId={} state={} confKeys={} elapsedMs={}",
                dagId, runId, run.state(), configuration.keySet(), elapsedMs(started));
        return run;
    }

    public AirflowDagRun dagRun(String dagId, String runId) {
        return parseRun(get(dagRunsPath(dagId) + "/" + segment(runId), "get_dag_run"));
    }

    public AirflowDagRun awaitTerminalRun(
            String dagId, String runId, Duration pollInterval, Duration timeout) {
        long started = System.nanoTime();
        long deadline = System.nanoTime() + timeout.toNanos();
        int poll = 0;
        String lastState = "not_observed";
        while (System.nanoTime() < deadline) {
            AirflowDagRun run = dagRun(dagId, runId);
            lastState = run.state();
            poll++;
            LOG.debug("event=airflow_dag_poll dagId={} runId={} poll={} state={} elapsedMs={}",
                    dagId, runId, poll, lastState, elapsedMs(started));
            if (TERMINAL_STATES.contains(lastState)) {
                LOG.info("event=airflow_dag_terminal dagId={} runId={} state={} polls={} "
                                + "observedElapsedMs={} airflowDurationMs={}",
                        dagId, runId, lastState, poll, elapsedMs(started), run.duration().toMillis());
                return run;
            }
            try {
                Thread.sleep(pollInterval);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AirflowApiException("Interrupted while observing Airflow DAG run", failure);
            }
        }
        throw new AirflowRunTimeoutException(dagId, runId, lastState);
    }

    public Map<String, AirflowTaskInstance> taskInstances(String dagId, String runId) {
        JsonNode root = get(dagRunsPath(dagId) + "/" + segment(runId) + "/taskInstances",
                "list_task_instances");
        JsonNode values = root.get("task_instances");
        if (values == null || !values.isArray()) {
            throw new AirflowApiException(
                    "Airflow task-instance response has no task_instances array", 200);
        }
        Map<String, AirflowTaskInstance> tasks = new LinkedHashMap<>();
        for (JsonNode value : values) {
            String id = requiredText(value, "task_id", "task_instances[].task_id");
            String state = requiredText(value, "state", "task_instances[].state");
            int tryNumber = value.path("try_number").asInt(0);
            tasks.put(id, new AirflowTaskInstance(id, state, tryNumber,
                    optionalInstant(value.get("start_date")), optionalInstant(value.get("end_date"))));
        }
        return Map.copyOf(tasks);
    }

    private JsonNode get(String path, String operation) {
        HttpRequest request = request(path).GET().build();
        HttpResponse<String> response = send(http, request, operation);
        requireSuccess(response, operation);
        return parse(json, response.body(), operation + " response");
    }

    private JsonNode sendJson(
            String method, String path, Map<String, ?> body, String operation) {
        final String serialized;
        try {
            serialized = json.writeValueAsString(body);
        } catch (JsonProcessingException failure) {
            throw new AirflowApiException("Cannot serialize Airflow " + operation + " request", failure);
        }
        HttpRequest request = request(path)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(serialized))
                .build();
        HttpResponse<String> response = send(http, request, operation);
        requireSuccess(response, operation);
        return parse(json, response.body(), operation + " response");
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + bearerToken)
                .header("Accept", "application/json");
    }

    private static HttpResponse<String> send(
            HttpClient http, HttpRequest request, String operation) {
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            LOG.debug("event=airflow_api_request operation={} method={} path={} status={} elapsedMs={}",
                    operation, request.method(), request.uri().getRawPath(), response.statusCode(),
                    elapsedMs(started));
            return response;
        } catch (IOException failure) {
            throw new AirflowApiException("Airflow " + operation + " transport failed", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AirflowApiException("Airflow " + operation + " transport interrupted", failure);
        }
    }

    private static void requireSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AirflowApiException(
                    "Airflow " + operation + " failed with HTTP " + response.statusCode()
                            + " (response body redacted)", response.statusCode());
        }
    }

    private static JsonNode parse(ObjectMapper json, String body, String label) {
        try {
            return json.readTree(body);
        } catch (JsonProcessingException failure) {
            throw new AirflowApiException("Airflow " + label + " is not valid JSON", failure);
        }
    }

    private static AirflowDagRun parseRun(JsonNode root) {
        return new AirflowDagRun(
                requiredText(root, "dag_run_id", "dag_run.dag_run_id"),
                requiredText(root, "state", "dag_run.state"),
                optionalInstant(root.get("start_date")),
                optionalInstant(root.get("end_date")));
    }

    private static String requiredText(JsonNode parent, String field, String label) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new AirflowApiException("Airflow response has no textual " + label, 200);
        }
        return value.asText();
    }

    private static Instant optionalInstant(JsonNode value) {
        return value == null || value.isNull() ? null : Instant.parse(value.asText());
    }

    private static String dagRunsPath(String dagId) {
        return "/api/v2/dags/" + segment(dagId) + "/dagRuns";
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static long elapsedMs(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    @Override
    public void close() {
        // Java 21's HttpClient owns no user-managed executor in this configuration.
    }
}
