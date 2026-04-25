package org.mule.extension.agent.composer.internal.source;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.configs.AgentSkillConfig;
import org.mule.extension.agent.composer.internal.configs.McpServerConfig;
import org.mule.extension.agent.composer.internal.mcp.McpClient;
import org.mule.extension.agent.composer.internal.model.ToolDefinition;
import org.mule.runtime.http.api.server.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds an A2A-compliant agent card JSON from the connector configuration
 * and any tools discovered from configured MCP servers.
 *
 * <p>The card is generated once when the Agent Listener starts. If an MCP server
 * is unreachable, its tools are skipped (logged at WARN) and a default
 * "text-reasoning" skill is included as a fallback.
 */
public class AgentCardBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentCardBuilder.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AgentCardBuilder() {}

    /**
     * Builds the A2A agent card with an absolute URL derived from the HTTP server's
     * bound address (host + port). If the listener is bound to a wildcard address,
     * the builder falls back to the Mule host system property and then to {@code localhost}.
     */
    public static String build(AgentComposerConfiguration config, HttpServer httpServer) {
        String normalizedPath = config.getAgentPath().startsWith("/")
                ? config.getAgentPath() : "/" + config.getAgentPath();

        // Resolve host from the bound server address when possible, then fall back
        // to mule.host and finally localhost for wildcard listener bindings.
        String host = System.getProperty("mule.host", "localhost");
        int port = 8081;
        try {
            String mulePort = System.getProperty("mule.port");
            if (mulePort != null && !mulePort.isEmpty()) {
                port = Integer.parseInt(mulePort);
            }
        } catch (NumberFormatException ignored) {}

        if (httpServer != null) {
            try {
                port = httpServer.getServerAddress().getPort();
                String boundIp = httpServer.getServerAddress().getIp();
                if (boundIp != null && !boundIp.isEmpty()
                        && !boundIp.equals("0.0.0.0") && !boundIp.equals("::")) {
                    host = boundIp;
                }
            } catch (Exception ignored) {}
        }

        String scheme = (port == 443 || port == 8443) ? "https" : "http";
        String agentUrl = scheme + "://" + host + ":" + port + normalizedPath;

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("protocolVersion", "0.3.0");
        card.put("name", nonEmpty(config.getAgentName(), "Agent Composer"));
        card.put("description", nonEmpty(config.getAgentDescription(), "A ReAct agent powered by Agent Composer."));
        card.put("version", nonEmpty(config.getAgentVersion(), "1.0.0"));
        card.put("url", agentUrl);
        card.put("preferredTransport", "JSONRPC");
        card.put("additionalInterfaces", Collections.singletonList(interfaceEntry(agentUrl, "JSONRPC")));

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("streaming", true);
        capabilities.put("pushNotifications", false);
        capabilities.put("stateTransitionHistory", false);
        card.put("capabilities", capabilities);

        card.put("defaultInputModes", Collections.singletonList("text/plain"));
        card.put("defaultOutputModes", List.of("text/plain", "application/json"));
        card.put("skills", buildSkills(config.getMcpServers(), config.getSkills(),
                config.isIncludeMcpToolsAsSkills()));

        return GSON.toJson(card);
    }

    /** @deprecated Use {@link #build(AgentComposerConfiguration, HttpServer)} for an absolute URL. */
    @Deprecated
    public static String build(AgentComposerConfiguration config) {
        return build(config, null);
    }

    private static List<Map<String, Object>> buildSkills(List<McpServerConfig> mcpServers,
                                                          List<AgentSkillConfig> configuredSkills,
                                                          boolean includeMcpToolsAsSkills) {
        List<Map<String, Object>> skills = new ArrayList<>();

        // Configured skills go first — only name + description to avoid context overflow
        if (configuredSkills != null && !configuredSkills.isEmpty()) {
            for (AgentSkillConfig skill : configuredSkills) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", skill.getName());
                entry.put("name", skill.getName());
                entry.put("description", nonEmpty(skill.getDescription(), ""));
                entry.put("tags", (skill.getTags() != null && !skill.getTags().isEmpty())
                        ? skill.getTagList() : Collections.emptyList());
                entry.put("inputModes", Collections.singletonList("text/plain"));
                entry.put("outputModes", List.of("text/plain", "application/json"));
                skills.add(entry);
            }
        }

        // MCP tool skills (auto-discovered) — controlled by the includeMcpToolsAsSkills flag.
        if (includeMcpToolsAsSkills && mcpServers != null && !mcpServers.isEmpty()) {
            McpClient mcpClient = new McpClient();
            for (McpServerConfig server : mcpServers) {
                try {
                    List<ToolDefinition> tools = mcpClient.listTools(server);
                    List<String> whitelist = server.getToolFilterList();
                    for (ToolDefinition tool : tools) {
                        if (!whitelist.isEmpty() && !whitelist.contains(tool.getName())) {
                            continue; // skip non-whitelisted tools
                        }
                        Map<String, Object> skill = new LinkedHashMap<>();
                        skill.put("id", tool.getName());
                        skill.put("name", tool.getName());
                        skill.put("description", nonEmpty(tool.getDescription(), ""));
                        skill.put("tags", Collections.emptyList());
                        skill.put("inputModes", Collections.singletonList("text/plain"));
                        skill.put("outputModes", List.of("text/plain", "application/json"));
                        skills.add(skill);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Could not fetch tools from MCP server '{}' for agent card: {}",
                            server.getName(), e.getMessage());
                }
            }
        }

        if (skills.isEmpty()) {
            Map<String, Object> defaultSkill = new LinkedHashMap<>();
            defaultSkill.put("id", "text-reasoning");
            defaultSkill.put("name", "Text Reasoning");
            defaultSkill.put("description", "Answers questions and performs tasks using language model reasoning.");
            defaultSkill.put("tags", Collections.emptyList());
            defaultSkill.put("inputModes", Collections.singletonList("text/plain"));
            defaultSkill.put("outputModes", List.of("text/plain", "application/json"));
            skills.add(defaultSkill);
        }

        return skills;
    }

    private static Map<String, Object> interfaceEntry(String url, String transport) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("url", url);
        entry.put("transport", transport);
        return entry;
    }

    private static String nonEmpty(String value, String fallback) {
        return (value == null || value.trim().isEmpty()) ? fallback : value.trim();
    }
}
