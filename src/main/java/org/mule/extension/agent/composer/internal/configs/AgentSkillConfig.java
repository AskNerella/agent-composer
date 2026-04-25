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
 * </ul>
 * All MCP tools configured on the parent agent are available to the skill;
 * the skill's own instructions guide which ones to use.
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
    @DisplayName("Tags")
    @Summary("Comma-separated tags advertising this skill's capabilities in the A2A agent card (e.g. 'jira, ticketing, issues').")
    @Optional
    @Placement(order = 4)
    private String tags;

    // ── getters ───────────────────────────────────────────────────────────────

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getInstructions() { return instructions; }
    public String getTags() { return tags; }

    /** Returns {@link #tags} split on commas, trimmed, with blank entries removed. */
    public List<String> getTagList() {
        if (tags == null || tags.trim().isEmpty()) return Collections.emptyList();
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toList());
    }
}
