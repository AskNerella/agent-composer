package org.mule.extension.agent.composer.internal.operations;

import static org.mule.sdk.api.annotation.param.MediaType.ANY;

import java.util.List;

import javax.inject.Inject;

import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.connection.AgentComposerConnection;
import org.mule.runtime.core.internal.registry.Registry;
import org.mule.runtime.extension.api.annotation.param.ParameterGroup;
import org.mule.sdk.api.annotation.param.MediaType;
import org.mule.sdk.api.annotation.param.Config;
import org.mule.sdk.api.annotation.param.Connection;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.runtime.extension.api.annotation.param.Optional;


public class AgentComposerOperations {

  @Inject
  private Registry registry;

  public String executeAgent(
    @Config AgentComposerConfiguration config,
    @Connection LLMConnection connection,
    @DisplayName("System Prompt (Instructions)") @Summary("Persona and task-framing instructions prepended to every LLM call as the system message.") 
    String instructions,
    @DisplayName("MCP Servers") @Optional @Summary("One or more MCP server endpoints whose tools are offered to the LLM.")
    List<McpServerConfig> mcpServers
  ) {
    return "";
  }
}
