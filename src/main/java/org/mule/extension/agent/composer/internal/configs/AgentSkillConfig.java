package org.mule.extension.agent.composer.internal.configs;

import org.mule.runtime.api.meta.ExpressionSupport;
import org.mule.runtime.extension.api.annotation.Expression;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.Parameter;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Placement;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.runtime.extension.api.annotation.param.display.Text;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Defines a named skill exposed in the A2A agent card and available to the ReAct engine.
 *
 * <p>When the LLM calls a skill, the engine runs a focused sub-loop using:
 * <ul>
 *   <li>{@code instructions} – injected as the system prompt for the sub-loop.</li>
 *   <li>{@code tools} – comma-separated whitelist of MCP tool names the skill may invoke
 *       (empty = all available MCP tools).</li>
 * </ul>
 *
 * <p>Only {@code name} and {@code description} are included in the A2A agent card to
 * avoid context overflow.
 */
public class AgentSkillConfig {

    @Parameter
    @DisplayName("Skill Name")
    @Summary("Unique identifier for this skill. The LLM will call the skill by this name.")
    @Placement(order = 1)
    private String name;

    @Parameter
    @DisplayName("Description")
    @Summary("Short description of what this skill does, shown in the A2A agent card.")
    @Placement(order = 2)
    private String description;

    @Parameter
    @DisplayName("Instructions")
    @Summary("System-prompt injected when this skill is executed. Supports DataWeave expressions.")
    @Text
    @Expression(ExpressionSupport.SUPPORTED)
    @Optional
    @Placement(order = 3)
    private String instructions;

    @Parameter
    @DisplayName("Tools")
    @Summary("Comma-separated list of MCP tool names this skill is allowed to use "
            + "(e.g. confluence_search,confluence_get_page). Leave empty to allow all tools.")
    @Optional
    @Placement(order = 4)
    private String tools;

    // ── getters ───────────────────────────────────────────────────────────────

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getInstructions() { return instructions; }

    /**
     * Returns the tool whitelist as a list by splitting the comma-separated {@code tools} string.
     * Returns an empty list (= allow all) when the field is blank or null.
     */
    public List<String> getToolList() {
        if (tools == null || tools.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(tools.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
