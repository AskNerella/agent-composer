package org.mule.extension.agent.composer.internal.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mule.extension.agent.composer.internal.configs.McpServerConfig;
import org.mule.sdk.api.exception.ModuleException;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class McpClientRetryTest {
    private HttpServer server;
    private McpServerConfig config;
    private final AtomicInteger listAttempts = new AtomicInteger();
    private volatile boolean alwaysFail;

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
        config = new McpServerConfig();
        setField(config, "name", "retry-server");
        setField(config, "serverUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    @After
    public void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    public void retriesConfiguredNumberOfTimes() throws Exception {
        setField(config, "retryCount", 1);
        assertEquals(0, new McpClient().listTools(config).size());
        assertEquals(2, listAttempts.get());
    }

    @Test
    public void defaultZeroDoesNotRetry() throws Exception {
        alwaysFail = true;
        try {
            new McpClient().listTools(config);
            fail("Expected MCP request failure");
        } catch (ModuleException expected) {
            assertEquals(1, listAttempts.get());
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject payload = JsonParser.parseString(body).getAsJsonObject();
        String method = payload.has("method") ? payload.get("method").getAsString() : "";
        if ("initialize".equals(method)) {
            exchange.getResponseHeaders().add("Mcp-Session-Id", "retry-session");
            respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-11-25\"}}");
        } else if ("notifications/initialized".equals(method)) {
            respond(exchange, 202, "");
        } else if ("tools/list".equals(method)) {
            int attempt = listAttempts.incrementAndGet();
            if (alwaysFail || attempt == 1) {
                respond(exchange, 503, "temporary failure");
            } else {
                respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}");
            }
        } else {
            respond(exchange, 404, "unknown");
        }
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
