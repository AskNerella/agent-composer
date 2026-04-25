package org.mule.extension.agent.composer.internal;

import org.mule.extension.agent.composer.internal.configs.AgentSkillConfig;
import org.mule.extension.agent.composer.internal.configs.McpServerConfig;
import org.mule.extension.agent.composer.internal.enums.LlmProvider;
import org.mule.extension.agent.composer.internal.operations.AgentComposerOperations;
import org.mule.extension.agent.composer.internal.source.AgentListenerSource;
import org.mule.extension.agent.composer.internal.values.HttpListenerConfigValueProvider;
import org.mule.extension.agent.composer.internal.values.ModelNameValueProvider;
import org.mule.runtime.api.meta.ExpressionSupport;
import org.mule.runtime.extension.api.annotation.Expression;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Password;
import org.mule.runtime.extension.api.annotation.param.display.Placement;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.runtime.extension.api.annotation.param.display.Text;
import org.mule.runtime.extension.api.annotation.values.OfValues;
import org.mule.sdk.api.annotation.Operations;
import org.mule.sdk.api.annotation.connectivity.ConnectionProviders;
import org.mule.sdk.api.annotation.param.Parameter;
import org.mule.sdk.api.annotation.param.reference.ObjectStoreReference;

import java.util.List;

@Operations(AgentComposerOperations.class)
@org.mule.sdk.api.annotation.Sources(AgentListenerSource.class)
@ConnectionProviders({})
public class AgentComposerConfiguration {

  // ── General ───────────────────────────────────────────────────────────────

  @Parameter
  @DisplayName("LLM Provider")
  @Expression(ExpressionSupport.SUPPORTED)
  @Summary("Inference provider: OPENAI or ANTHROPIC.")
  @Optional(defaultValue = "OPENAI")
  @Placement(order = 1)
  private LlmProvider provider;

  @Parameter
  @DisplayName("Model Name")
  @Expression(ExpressionSupport.SUPPORTED)
  @Summary("Model identifier, e.g. gpt-4o, claude-3-5-sonnet-20241022.")
  @OfValues(ModelNameValueProvider.class)
  @Optional(defaultValue = "gpt-4o")
  @Placement(order = 2)
  private String modelName;

  @Parameter
  @DisplayName("Anthropic API Version")
  @Summary("Anthropic-Version header value (only used when provider is ANTHROPIC).")
  @Optional(defaultValue = "2023-06-01")
  @Placement(order = 3)
  private String anthropicVersion;

  @Parameter
  @DisplayName("API Key")
  @Password
  @Expression(ExpressionSupport.SUPPORTED)
  @Summary("API key for the configured LLM provider.")
  @Optional
  @Placement(order = 4)
  private String apiKey;

  @Parameter
  @DisplayName("Max Output Tokens")
  @Expression(ExpressionSupport.SUPPORTED)
  @Optional(defaultValue = "1000")
  @Placement(order = 5)
  private int maxTokens;

  @Parameter
  @DisplayName("Temperature")
  @Summary("Sampling temperature (0–2). Higher values = more random outputs.")
  @Optional(defaultValue = "0.7")
  @Placement(order = 6)
  private double temperature;

  @Parameter
  @DisplayName("HTTP Listener Config")
  @Summary("Select the <http:listener-config> global element this agent attaches to.")
  @OfValues(HttpListenerConfigValueProvider.class)
  @Optional(defaultValue = "HTTP_Listener_config")
  @Placement(order = 7)
  private String httpListenerConfig;

  @Parameter
  @DisplayName("Agent Path")
  @Summary("HTTP path where A2A task POST requests arrive (e.g. /agent).")
  @Optional(defaultValue = "/agent")
  @Placement(order = 8)
  private String agentPath;

  @Parameter
  @DisplayName("Public Host")
  @Summary("Publicly reachable hostname or IP for this agent (used in the A2A agent card URL). "
          + "Leave blank to use the HTTP listener's bound address.")
  @Optional
  @Placement(order = 9)
  private String publicHost;

  @Parameter
  @DisplayName("Request Timeout (seconds)")
  @Summary("Seconds to wait for the Mule flow to produce a response before returning HTTP 504.")
  @Optional(defaultValue = "300")
  @Placement(order = 9)
  private int requestTimeoutSeconds;

  @Parameter
  @DisplayName("Max Iterations")
  @Summary("Maximum ReAct loop iterations per request (applies to both streaming and non-streaming).")
  @Optional(defaultValue = "10")
  @Placement(order = 10)
  private int maxIterations;

  // ── Agent ─────────────────────────────────────────────────────────────────

  @Parameter
  @DisplayName("Instructions")
  @Summary("System prompt / persona prepended to every LLM call.")
  @Text
  @Optional
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
  @Optional(defaultValue = "true")
  @Placement(tab = "Agent Card", order = 5)
  private boolean includeMcpToolsAsSkills;

  // ── Getters ───────────────────────────────────────────────────────────────

  public LlmProvider getProvider()            { return provider; }
  public String getModelName()                { return modelName; }
  public String getApiKey()                   { return apiKey; }
  public int getMaxTokens()                   { return maxTokens; }
  public double getTemperature()              { return temperature; }
  public String getAnthropicVersion()         { return anthropicVersion; }
  public String getInstructions()             { return instructions; }
  public List<McpServerConfig> getMcpServers(){ return mcpServers; }
  public String getObjectStore()              { return objectStore; }
  public String getHttpListenerConfig()       { return httpListenerConfig; }
  public String getAgentPath()                { return agentPath; }
  public String getPublicHost()               { return publicHost; }
  public int getRequestTimeoutSeconds()       { return requestTimeoutSeconds; }
  public String getAgentName()                { return agentName; }
  public String getAgentDescription()         { return agentDescription; }
  public String getAgentVersion()             { return agentVersion; }
  public List<AgentSkillConfig> getSkills()   { return skills; }
  public boolean isIncludeMcpToolsAsSkills()  { return includeMcpToolsAsSkills; }
  public int getMaxIterations()               { return maxIterations <= 0 ? 10 : maxIterations; }
}
