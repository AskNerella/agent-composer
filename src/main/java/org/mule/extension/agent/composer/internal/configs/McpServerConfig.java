package org.mule.extension.agent.composer.internal.configs;

import org.mule.runtime.extension.api.annotation.param.Parameter;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Password;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.runtime.extension.api.annotation.param.display.Placement;
import org.mule.runtime.extension.api.annotation.param.Optional;

public class McpServerConfig {
    @Parameter
    @DisplayName("Client Name")
    @Summary("Logical name used to identify this MCP client (e.g. 'weather-service').")
    @Placement(order = 1)
    private String name;

    @Parameter
    @DisplayName("Server URL")
    @Summary("JSON-RPC 2.0 endpoint of the MCP server (e.g. http://host/mcp).")
    @Placement(order = 2)
    private String serverUrl;

    @Parameter
    @DisplayName("Auth Token")
    @Password
    @Optional
    @Summary("Optional Bearer token for authenticating requests to this MCP server.")
    @Placement(order = 3)
    private String authToken;

    public String getName() { return name; }

    public String getServerUrl() { return serverUrl; }

    public String getAuthToken() { return authToken; }
}
