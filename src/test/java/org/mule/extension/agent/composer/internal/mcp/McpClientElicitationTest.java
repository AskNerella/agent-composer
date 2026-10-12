package org.mule.extension.agent.composer.internal.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mule.extension.agent.composer.internal.configs.McpServerConfig;
import org.mule.extension.agent.composer.internal.model.McpToolResult;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class McpClientElicitationTest {

    private HttpServer server;
    private ExecutorService serverExecutor;
    private McpServerConfig config;
    private final AtomicReference<JsonObject> elicitationResponse = new AtomicReference<>();
    private final AtomicReference<String> protocolHeader = new AtomicReference<>();
    private final AtomicReference<String> sessionHeader = new AtomicReference<>();
    private volatile long originalRequestId;
    private volatile boolean standardStreamMode;
    private volatile String negotiatedProtocolVersion = "2025-11-25";
    private volatile CountDownLatch elicitationAnswered;

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/mcp", this::handle);
        server.start();
        config = new McpServerConfig();
        setField(config, "name", "test-server");
        setField(config, "serverUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    @After
    public void tearDown() {
        if (server != null) server.stop(0);
        if (serverExecutor != null) serverExecutor.shutdownNow();
    }

    @Test
    public void pausesAndResumesAcceptedFormOnPersistedSession() throws Exception {
        McpClient client = new McpClient();
        McpToolResult.CallOutcome paused = client.callTool(
                config, "book_trip", Collections.singletonMap("destination", "DTW"), null);

        assertTrue(paused.isElicitationRequired());
        assertEquals("session-123", paused.getElicitation().getSessionId());
        assertEquals("Which cabin?", paused.getElicitation().getMessage());

        McpToolResult.CallOutcome completed = new McpClient().resolveElicitation(
                config, paused.getElicitation(), "accept",
                Collections.singletonMap("cabin", "economy"));

        assertFalse(completed.isElicitationRequired());
        assertEquals("booked", completed.getResult().getTextContent());
        assertEquals("2025-11-25", protocolHeader.get());
        assertEquals("session-123", sessionHeader.get());
        assertEquals("accept", elicitationResponse.get().getAsJsonObject("result").get("action").getAsString());
        assertEquals("economy", elicitationResponse.get().getAsJsonObject("result")
                .getAsJsonObject("content").get("cabin").getAsString());
    }

    @Test
    public void sendsDeclineAndCancelWithoutContent() throws Exception {
        McpClient client = new McpClient();
        McpToolResult.CallOutcome paused = client.callTool(config, "book_trip", Collections.emptyMap(), null);
        new McpClient().resolveElicitation(config, paused.getElicitation(), "decline", null);
        JsonObject result = elicitationResponse.get().getAsJsonObject("result");
        assertEquals("decline", result.get("action").getAsString());
        assertFalse(result.has("content"));

        new McpClient().resolveElicitation(config, paused.getElicitation(), "cancel", null);
        result = elicitationResponse.get().getAsJsonObject("result");
        assertEquals("cancel", result.get("action").getAsString());
        assertFalse(result.has("content"));
    }

    @Test
    public void resumesFromOriginalStreamWhenResponsePostIsOnlyAcknowledged() throws Exception {
        standardStreamMode = true;
        negotiatedProtocolVersion = "2025-06-18";
        elicitationAnswered = new CountDownLatch(1);

        McpToolResult.CallOutcome paused = new McpClient().callTool(
                config, "book_trip", Collections.singletonMap("destination", "DTW"), null);

        assertTrue(paused.isElicitationRequired());
        assertEquals("2025-06-18", paused.getElicitation().getProtocolVersion());

        McpToolResult.CallOutcome completed = new McpClient().resolveElicitation(
                config, paused.getElicitation(), "accept",
                Collections.singletonMap("cabin", "economy"));

        assertFalse(completed.isElicitationRequired());
        assertEquals("booked-on-original-stream", completed.getResult().getTextContent());
        assertEquals("2025-06-18", protocolHeader.get());
        assertTrue(elicitationAnswered.await(1, TimeUnit.SECONDS));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject payload = body.isEmpty() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
        String method = payload.has("method") ? payload.get("method").getAsString() : null;
        protocolHeader.set(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
        sessionHeader.set(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));

        if ("initialize".equals(method)) {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Mcp-Session-Id", "session-123");
            respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"protocolVersion\":\""
                    + negotiatedProtocolVersion
                    + "\",\"capabilities\":{},\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
            return;
        }
        if ("notifications/initialized".equals(method)) {
            respond(exchange, 202, "");
            return;
        }
        if ("tools/call".equals(method)) {
            originalRequestId = payload.get("id").getAsLong();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            if (standardStreamMode) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write((
                        "data: {\"jsonrpc\":\"2.0\",\"id\":\"elicit-1\",\"method\":\"elicitation/create\",\"params\":{\"mode\":\"form\",\"message\":\"Which cabin?\",\"requestedSchema\":{\"type\":\"object\",\"properties\":{\"cabin\":{\"type\":\"string\"}},\"required\":[\"cabin\"]}}}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                try {
                    if (!elicitationAnswered.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting for elicitation response");
                    }
                    exchange.getResponseBody().write(("data: {\"jsonrpc\":\"2.0\",\"id\":" + originalRequestId
                            + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"booked-on-original-stream\"}]}}\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                } finally {
                    exchange.close();
                }
                return;
            }
            respond(exchange, 200,
                    "data: {\"jsonrpc\":\"2.0\",\"id\":\"elicit-1\",\"method\":\"elicitation/create\",\"params\":{\"mode\":\"form\",\"message\":\"Which cabin?\",\"requestedSchema\":{\"type\":\"object\",\"properties\":{\"cabin\":{\"type\":\"string\"}},\"required\":[\"cabin\"]}}}\n\n");
            return;
        }

        elicitationResponse.set(payload);
        if (standardStreamMode) {
            respond(exchange, 202, "");
            elicitationAnswered.countDown();
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        respond(exchange, 200, "data: {\"jsonrpc\":\"2.0\",\"id\":" + originalRequestId
                + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"booked\"}]}}\n\n");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
