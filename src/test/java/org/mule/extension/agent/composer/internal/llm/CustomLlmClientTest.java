package org.mule.extension.agent.composer.internal.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.configs.CustomHttpHeader;
import org.mule.extension.agent.composer.internal.enums.LlmProvider;
import org.mule.extension.agent.composer.internal.model.LlmMessage;
import org.mule.extension.agent.composer.internal.model.LlmResponse;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CustomLlmClientTest {
    private HttpServer server;
    private final AtomicReference<String> tenantHeader = new AtomicReference<>();
    private final AtomicReference<String> authorizationHeader = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", this::handle);
        server.start();
    }

    @After
    public void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    public void customProviderUsesConfiguredEndpointAndHeaders() throws Exception {
        AgentComposerConfiguration config = new AgentComposerConfiguration();
        setField(config, "provider", LlmProvider.CUSTOM);
        setField(config, "modelName", "gateway-model");
        setField(config, "apiKey", "gateway-token");
        setField(config, "maxTokens", 321);
        setField(config, "temperature", 0.2d);
        setField(config, "customLlmEndpointUrl",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/responses");
        CustomHttpHeader header = new CustomHttpHeader();
        setField(header, "name", "X-Tenant-Id");
        setField(header, "value", "tenant-42");
        setField(config, "customLlmHeaders", Collections.singletonList(header));

        LlmResponse response = LlmClientFactory.create(config).chat("Be concise",
                Collections.singletonList(new LlmMessage("user", "hello")), Collections.emptyList());

        assertEquals("custom-ok", response.getContent());
        assertEquals("tenant-42", tenantHeader.get());
        assertEquals("Bearer gateway-token", authorizationHeader.get());
        assertTrue(requestBody.get().contains("\"model\":\"gateway-model\""));
        assertTrue(requestBody.get().contains("\"max_output_tokens\":321"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        tenantHeader.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
        authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] response = ("{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\","
                + "\"text\":\"custom-ok\"}]}],\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
