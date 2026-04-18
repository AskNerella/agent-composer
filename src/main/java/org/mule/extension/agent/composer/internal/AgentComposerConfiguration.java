package org.mule.extension.agent.composer.internal;

import org.mule.extension.agent.composer.internal.connection.AgentComposerConnectionProvider;
import org.mule.extension.agent.composer.internal.enums.LlmProvider;
import org.mule.extension.agent.composer.internal.operations.AgentComposerOperations;
import org.mule.runtime.api.meta.ExpressionSupport;
import org.mule.runtime.extension.api.annotation.Expression;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Placement;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.sdk.api.annotation.Operations;
import org.mule.sdk.api.annotation.connectivity.ConnectionProviders;
import org.mule.sdk.api.annotation.param.Parameter;


@Operations(AgentComposerOperations.class)
@ConnectionProviders({})
public class AgentComposerConfiguration {

  // LLM Provider (e.g. OpenAI, Anthropic)
  @Parameter
  @DisplayName("LLM Provider")
  @Summary("Inference provider to use: OPENAI or ANTHROPIC.")
  @Optional(defaultValue = "OPENAI")
  @Placement(order = 1)
  private LlmProvider provider;

  // Model Name (e.g. gpt-4, claude-2)
  @Parameter
  @DisplayName("Model Name")
  @Summary("Model to use for inference, e.g. gpt-4, gpt-3.5-turbo, claude-2, etc.")
  @Optional(defaultValue = "gpt-4")
  @Placement(order = 2)
  private String modelName;

  // API Key
  @Parameter
  @DisplayName("API Key")
  @Expression(ExpressionSupport.SUPPORTED)
  @Summary("API key for the chosen LLM provider.")
  @Optional
  @Placement(order = 3)
  private String apiKey;
  
  // Max Token
  @Parameter
  @DisplayName("Max Tokens")
  @Expression(ExpressionSupport.SUPPORTED)
  @Optional(defaultValue = "500")
  @Placement(order = 3)
  private Number maxTokens;
  
  // Temperature
  @Parameter
  @DisplayName("Temperature")
  @Summary("Sampling temperature to use, between 0 and 2. Higher values mean the model will take more risks.")
  @Optional(defaultValue = "0.9")
  @Placement(order = 5)
  private Double temperature;


  public LlmProvider getProvider() {
    return provider;
  }

  public String getModelName() {
    return modelName;
  }

  public String getApiKey() {
    return apiKey;
  }

  public Number getMaxTokens() {
    return maxTokens;
  }

  public Double getTemperature() {
    return temperature;
  }

}
