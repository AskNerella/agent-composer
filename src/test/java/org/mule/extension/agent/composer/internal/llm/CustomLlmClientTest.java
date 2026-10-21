package org.mule.extension.agent.composer.internal.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.configs.LlmHttpHeader;
import org.mule.extension.agent.composer.internal.configs.OmniGatewayProviderConfig;
import org.mule.extension.agent.composer.internal.configs.OpenAiCompatibleProviderConfig;
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
        server.createContext("/gateway/openai/v1/responses", this::handle);
        server.createContext("/omni/model-proxy/responses", this::handle);
        server.start();
    }

    @After
    public void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    public void customProviderUsesConfiguredEndpointAndHeaders() throws Exception {
        AgentComposerConfiguration config = new AgentComposerConfiguration();
        setField(config, "maxTokens", 321);
        setField(config, "temperature", 0.2d);
        LlmHttpHeader header = new LlmHttpHeader();
        setField(header, "name", "X-Tenant-Id");
        setField(header, "value", "tenant-42");
        LlmHttpHeader authorization = new LlmHttpHeader();
        setField(authorization, "name", "Authorization");
        setField(authorization, "value", "Bearer gateway-token");
        OpenAiCompatibleProviderConfig compatible = new OpenAiCompatibleProviderConfig();
        setField(compatible, "modelName", "gateway-model");
        setField(compatible, "endpointUrl",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/responses");
        setField(compatible, "headers", java.util.Arrays.asList(header, authorization));
        setField(config, "llmProvider", compatible);

        LlmResponse response = LlmClientFactory.create(config).chat("Be concise",
                Collections.singletonList(new LlmMessage("user", "hello")), Collections.emptyList());

        assertEquals("custom-ok", response.getContent());
        assertEquals("tenant-42", tenantHeader.get());
        assertEquals("Bearer gateway-token", authorizationHeader.get());
        assertTrue(requestBody.get().contains("\"model\":\"gateway-model\""));
        assertTrue(requestBody.get().contains("\"max_output_tokens\":321"));
    }

    @Test
    public void customProviderAppendsResponsesPathToBaseUrl() throws Exception {
        AgentComposerConfiguration config = new AgentComposerConfiguration();
        setField(config, "maxTokens", 321);
        setField(config, "temperature", 1.0d);
        OpenAiCompatibleProviderConfig compatible = new OpenAiCompatibleProviderConfig();
        setField(compatible, "modelName", "gateway-model");
        setField(compatible, "endpointUrl",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/gateway/openai/");
        setField(config, "llmProvider", compatible);

        LlmClient client = LlmClientFactory.create(config);
        LlmResponse response = client.chat("Be concise",
                Collections.singletonList(new LlmMessage("user", "hello")), Collections.emptyList());

        assertEquals("custom-ok", response.getContent());
        assertEquals("http://127.0.0.1:" + server.getAddress().getPort()
                + "/gateway/openai/v1/responses", client.requestMetadata().get("url"));
    }

    @Test
    public void omniGatewayUsesModelProxyResponsesPathAndRequiredModel() throws Exception {
        AgentComposerConfiguration config = new AgentComposerConfiguration();
        setField(config, "maxTokens", 321);
        setField(config, "temperature", 1.0d);
        LlmHttpHeader clientId = new LlmHttpHeader();
        setField(clientId, "name", "client_id");
        setField(clientId, "value", "client-42");
        OmniGatewayProviderConfig omni = new OmniGatewayProviderConfig();
        setField(omni, "endpointUrl",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/omni/model-proxy");
        setField(omni, "headers", Collections.singletonList(clientId));
        setField(omni, "modelName", "openai/gpt-5.2");
        setField(config, "llmProvider", omni);

        LlmClient client = LlmClientFactory.create(config);
        LlmResponse response = client.chat("Be concise",
                Collections.singletonList(new LlmMessage("user", "hello")), Collections.emptyList());

        assertEquals("custom-ok", response.getContent());
        assertEquals("http://127.0.0.1:" + server.getAddress().getPort()
                + "/omni/model-proxy/responses", client.requestMetadata().get("url"));
        assertEquals("client-42", tenantHeader.get());
        assertTrue(requestBody.get().contains("\"model\":\"openai/gpt-5.2\""));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String tenant = exchange.getRequestHeaders().getFirst("X-Tenant-Id");
        tenantHeader.set(tenant != null ? tenant : exchange.getRequestHeaders().getFirst("client_id"));
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
