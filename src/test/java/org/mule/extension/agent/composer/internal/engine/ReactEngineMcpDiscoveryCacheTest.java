package org.mule.extension.agent.composer.internal.engine;

import org.junit.Test;
import org.mule.extension.agent.composer.internal.configs.McpServerConfig;
import org.mule.extension.agent.composer.internal.mcp.McpClient;
import org.mule.extension.agent.composer.internal.model.ToolDefinition;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ReactEngineMcpDiscoveryCacheTest {

    @Test
    public void reusesDiscoveredToolsAcrossEngineInstances() throws Exception {
        McpClient client = mock(McpClient.class);
        McpServerConfig server = new McpServerConfig();
        set(server, "name", "cache-test-" + UUID.randomUUID());
        set(server, "serverUrl", "http://example.invalid/mcp");
        when(client.listTools(server)).thenReturn(Collections.singletonList(
                new ToolDefinition("lookup", "Lookup", Collections.emptyMap(), server.getName())));

        discover(new ReactEngine(null, client), server);
        discover(new ReactEngine(null, client), server);

        verify(client, times(1)).listTools(server);
    }

    private static void discover(ReactEngine engine, McpServerConfig server) throws Exception {
        Method method = ReactEngine.class.getDeclaredMethod("discoverMcpTools", java.util.List.class);
        method.setAccessible(true);
        method.invoke(engine, Collections.singletonList(server));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
