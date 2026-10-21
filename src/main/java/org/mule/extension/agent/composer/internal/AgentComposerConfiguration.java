package org.mule.extension.agent.composer.internal;

import org.mule.extension.agent.composer.internal.configs.AgentSkillConfig;
import org.mule.extension.agent.composer.internal.configs.LlmHttpHeader;
import org.mule.extension.agent.composer.internal.configs.LlmProviderConfig;
import org.mule.extension.agent.composer.internal.configs.McpServerConfig;
import org.mule.extension.agent.composer.internal.enums.LlmProvider;
import org.mule.extension.agent.composer.internal.operations.AgentComposerOperations;
import org.mule.extension.agent.composer.internal.source.AgentListenerSource;
import org.mule.runtime.api.meta.ExpressionSupport;
import org.mule.runtime.extension.api.annotation.Expression;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Placement;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.runtime.extension.api.annotation.param.display.Text;
import org.mule.sdk.api.annotation.Operations;
import org.mule.sdk.api.annotation.connectivity.ConnectionProviders;
import org.mule.sdk.api.annotation.param.Parameter;
import org.mule.sdk.api.annotation.param.reference.ConfigReference;
import org.mule.sdk.api.annotation.param.reference.ObjectStoreReference;

import java.util.List;

@Operations(AgentComposerOperations.class)
@org.mule.sdk.api.annotation.Sources(AgentListenerSource.class)
@ConnectionProviders({})
public class AgentComposerConfiguration {

  // ── General ───────────────────────────────────────────────────────────────

  @Parameter
  @DisplayName("LLM Provider")
  @Summary("Select OpenAI, Anthropic, OpenAI Compatible, or MuleSoft Omni Gateway. Only relevant fields are displayed.")
  @Placement(order = 1)
  private LlmProviderConfig llmProvider;

  // Backward-compatible fallbacks for existing runtime tests and programmatic configuration.
  // These fields are intentionally not SDK parameters; Studio renders only the selected subtype.
  private LlmProvider provider;
  private String modelName;
  private String anthropicVersion;
  private String apiKey;
  private String llmEndpointUrl;
  private List<LlmHttpHeader> llmHeaders;

  @Parameter
  @DisplayName("Max Output Tokens")
  @Expression(ExpressionSupport.SUPPORTED)
  @Optional(defaultValue = "1000")
  @Placement(order = 7)
  private int maxTokens;

  @Parameter
  @DisplayName("Temperature")
  @Summary("Sampling temperature (0–2). Higher values = more random outputs.")
  @Optional(defaultValue = "1.0")
  @Placement(order = 8)
  private double temperature;

  @Parameter
  @DisplayName("HTTP Listener Config")
  @Expression(ExpressionSupport.NOT_SUPPORTED)
  @Summary("Select the <http:listener-config> global element this agent attaches to.")
  @ConfigReference(namespace = "HTTP", name = "LISTENER_CONFIG")
  @Optional(defaultValue = "HTTP_Listener_config")
  @Placement(order = 9)
  private String httpListenerConfig;

  @Parameter
  @DisplayName("Agent Path")
  @Summary("HTTP path where A2A task POST requests arrive (e.g. /agent).")
  @Optional(defaultValue = "/agent")
  @Placement(order = 10)
  private String agentPath;

  @Parameter
  @DisplayName("Request Timeout (seconds)")
  @Summary("Seconds to wait for the Mule flow to produce a response before returning HTTP 504.")
  @Optional(defaultValue = "300")
  @Placement(order = 11)
  private int requestTimeoutSeconds;

  @Parameter
  @DisplayName("Tool Permission Wait Time (seconds)")
  @Summary("How long to wait for a tool approval before the A2A task expires. Set to 0 to wait indefinitely.")
  @Optional(defaultValue = "900")
  @Placement(order = 12)
  private int toolPermissionWaitTimeSeconds;

  @Parameter
  @DisplayName("Traces Path")
  @Summary("HTTP path for the execution trace console.")
  @Optional(defaultValue = "/agent/traces")
  @Placement(order = 13)
  private String tracesPath;
  // ── Agent ─────────────────────────────────────────────────────────────────

  @Parameter
  @DisplayName("Instructions")
  @Summary("System prompt / persona prepended to every LLM call.")
  @Text
  @Optional(defaultValue = "${file::agent/instructions.txt}")
  @Placement(tab = "Agent", order = 1)
  private String instructions;

  @Parameter
  @DisplayName("MCP Servers")
  @Summary("MCP server endpoints whose tools are offered to the LLM.")
  @Optional
  @Placement(tab = "Agent", order = 2)
  private List<McpServerConfig> mcpServers;

  @Parameter
  @DisplayName("Memory (Object Store)")
  @Summary("Mule Object Store used to persist conversation history and memory.")
  @ObjectStoreReference
  @Optional(defaultValue = "_defaultPersistentObjectStore")
  @Placement(tab = "Agent", order = 3)
  private String objectStore;

  @Parameter
  @DisplayName("Remember Conversation")
  @Summary("Persist and reuse conversation history and solved responses. Disabled by default so each invocation starts fresh. MCP elicitation continuation state is still persisted while a call is paused.")
  @Optional(defaultValue = "false")
  @Placement(tab = "Agent", order = 4)
  private boolean rememberConversation;

  // ── Agent Card ────────────────────────────────────────────────────────────

  @Parameter
  @DisplayName("Agent Name")
  @Summary("Display name for this agent, advertised in the A2A agent card.")
  @Optional(defaultValue = "Agent Composer")
  @Placement(tab = "Agent Card", order = 1)
  private String agentName;

  @Parameter
  @DisplayName("Agent Description")
  @Summary("Short description of what this agent does, advertised in the A2A agent card.")
  @Optional(defaultValue = "A ReAct agent powered by Agent Composer.")
  @Placement(tab = "Agent Card", order = 2)
  private String agentDescription;

  @Parameter
  @DisplayName("Agent Version")
  @Summary("Version string advertised in the A2A agent card.")
  @Optional(defaultValue = "1.0.0")
  @Placement(tab = "Agent Card", order = 3)
  private String agentVersion;

  @Parameter
  @DisplayName("Skills")
  @Summary("Skills exposed in the A2A agent card. Each skill has its own instructions and allowed MCP tools.")
  @Optional
  @Placement(tab = "Agent Card", order = 4)
  private List<AgentSkillConfig> skills;

  @Parameter
  @DisplayName("Include MCP Tools as Skills")
  @Summary("When enabled, each MCP tool is advertised as an individual skill in the A2A agent card. "
          + "Disable to hide raw MCP tools from the card (e.g. when you have higher-level Skills configured).")
  @Optional(defaultValue = "false")
  @Placement(tab = "Agent Card", order = 5)
  private boolean includeMcpToolsAsSkills;

  // ── Getters ───────────────────────────────────────────────────────────────

  public LlmProvider getProvider()            { return llmProvider != null ? llmProvider.getProvider() : provider; }
  public String getModelName()                { return llmProvider != null ? llmProvider.getModelName() : modelName != null ? modelName : "gpt-4o"; }
  public String getApiKey()                   { return llmProvider != null ? llmProvider.getApiKey() : apiKey; }
  public String getLlmEndpointUrl()           { return llmProvider != null ? llmProvider.getEndpointUrl() : llmEndpointUrl; }
  public List<LlmHttpHeader> getLlmHeaders()  { return llmProvider != null ? llmProvider.getHeaders() : llmHeaders; }
  public int getMaxTokens()                   { return maxTokens; }
  public double getTemperature()              { return temperature; }
  public String getAnthropicVersion()         { return llmProvider != null ? llmProvider.getAnthropicVersion() : anthropicVersion != null ? anthropicVersion : "2023-06-01"; }
  public String getInstructions()             { return instructions; }
  public List<McpServerConfig> getMcpServers(){ return mcpServers; }
  public String getObjectStore()              { return objectStore; }
  public String getHttpListenerConfig()       { return httpListenerConfig; }
  public String getAgentPath()                { return agentPath; }
  public int getRequestTimeoutSeconds()       { return requestTimeoutSeconds; }
  public int getToolPermissionWaitTimeSeconds()  { return toolPermissionWaitTimeSeconds; }
  public String getTracesPath()               { return tracesPath == null || tracesPath.isBlank() ? "/agent/traces" : tracesPath; }
  public String getAgentName()                { return agentName; }
  public String getAgentDescription()         { return agentDescription; }
  public String getAgentVersion()             { return agentVersion; }
  public List<AgentSkillConfig> getSkills()   { return skills; }
  public boolean isIncludeMcpToolsAsSkills()  { return includeMcpToolsAsSkills; }
  public boolean isRememberConversation()     { return rememberConversation; }
  public LlmProviderConfig getLlmProvider()   { return llmProvider; }
}
