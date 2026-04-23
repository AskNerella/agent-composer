package org.mule.extension.agent.composer.internal.configs;

import org.mule.runtime.extension.api.annotation.param.Parameter;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Password;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.runtime.extension.api.annotation.param.display.Placement;
import org.mule.runtime.extension.api.annotation.param.Optional;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

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

    @Parameter
    @DisplayName("Tool Filter (Whitelist)")
    @Summary("Comma-separated list of tool names to include from this server (e.g. tool_a,tool_b). Leave empty to allow all tools.")
    @Optional
    @Placement(order = 4)
    private String toolFilters;

    public String getName() { return name; }
    public String getServerUrl() { return serverUrl; }
    public String getAuthToken() { return authToken; }

    /**
     * Returns the tool filter as a list by splitting the comma-separated string.
     * Returns an empty list if no filter is configured.
     */
    public List<String> getToolFilterList() {
        if (toolFilters == null || toolFilters.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(toolFilters.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
