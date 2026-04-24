package org.mule.extension.agent.composer.internal.source;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.configs.AgentSkillConfig;
import org.mule.extension.agent.composer.internal.configs.McpServerConfig;
import org.mule.extension.agent.composer.internal.mcp.McpClient;
import org.mule.extension.agent.composer.internal.model.ToolDefinition;
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
     * Builds the A2A agent card. The {@code url} field is left as a relative path
     * since the host/port are owned by the referenced {@code http:listener-config}.
     */
    public static String build(AgentComposerConfiguration config) {
        String normalizedPath = config.getAgentPath().startsWith("/")
                ? config.getAgentPath() : "/" + config.getAgentPath();
        // The url is relative — callers who need an absolute URL should prepend
        // the host/port from the referenced http:listener-config themselves.
        String agentUrl = normalizedPath;

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", nonEmpty(config.getAgentName(), "Agent Composer"));
        card.put("description", nonEmpty(config.getAgentDescription(), "A ReAct agent powered by Agent Composer."));
        card.put("version", nonEmpty(config.getAgentVersion(), "1.0.0"));
        card.put("url", agentUrl);

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("streaming", false);
        capabilities.put("pushNotifications", false);
        capabilities.put("stateTransitionHistory", true);
        card.put("capabilities", capabilities);

        card.put("defaultInputModes", Collections.singletonList("text"));
        card.put("defaultOutputModes", Collections.singletonList("text"));
        card.put("skills", buildSkills(config.getMcpServers(), config.getSkills()));

        return GSON.toJson(card);
    }

    private static List<Map<String, Object>> buildSkills(List<McpServerConfig> mcpServers,
                                                          List<AgentSkillConfig> configuredSkills) {
        List<Map<String, Object>> skills = new ArrayList<>();

        // Configured skills go first — only name + description to avoid context overflow
        if (configuredSkills != null && !configuredSkills.isEmpty()) {
            for (AgentSkillConfig skill : configuredSkills) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", skill.getName());
                entry.put("name", skill.getName());
                entry.put("description", nonEmpty(skill.getDescription(), ""));
                skills.add(entry);
            }
        }

        // MCP tool skills (auto-discovered)
        if (mcpServers != null && !mcpServers.isEmpty()) {
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
            skills.add(defaultSkill);
        }

        return skills;
    }

    private static String nonEmpty(String value, String fallback) {
        return (value == null || value.trim().isEmpty()) ? fallback : value.trim();
    }
}
