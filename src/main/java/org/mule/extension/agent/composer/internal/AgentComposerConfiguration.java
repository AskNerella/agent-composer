package org.mule.extension.agent.composer.internal;

import org.mule.extension.agent.composer.internal.enums.LlmProvider;
import org.mule.extension.agent.composer.internal.operations.AgentComposerOperations;
import org.mule.extension.agent.composer.internal.values.ModelNameValueProvider;
import org.mule.runtime.api.meta.ExpressionSupport;
import org.mule.runtime.extension.api.annotation.Expression;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Password;
import org.mule.runtime.extension.api.annotation.param.display.Placement;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.runtime.extension.api.annotation.values.OfValues;
import org.mule.sdk.api.annotation.Operations;
import org.mule.sdk.api.annotation.connectivity.ConnectionProviders;
import org.mule.sdk.api.annotation.param.Parameter;

@Operations(AgentComposerOperations.class)
@ConnectionProviders({})
public class AgentComposerConfiguration {

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
  @DisplayName("API Key")
  @Password
  @Expression(ExpressionSupport.SUPPORTED)
  @Summary("API key for the configured LLM provider.")
  @Optional
  @Placement(order = 3)
  private String apiKey;

  @Parameter
  @DisplayName("Max Output Tokens")
  @Expression(ExpressionSupport.SUPPORTED)
  @Optional(defaultValue = "1000")
  @Placement(order = 4)
  private int maxTokens;

  @Parameter
  @DisplayName("Temperature")
  @Summary("Sampling temperature (0–2). Higher values = more random outputs.")
  @Optional(defaultValue = "0.7")
  @Placement(order = 5)
  private double temperature;

  @Parameter
  @DisplayName("Anthropic API Version")
  @Summary("Anthropic-Version header value (only used when provider is ANTHROPIC).")
  @Optional(defaultValue = "2023-06-01")
  @Placement(tab = "Anthropic", order = 1)
  private String anthropicVersion;

  public LlmProvider getProvider() { return provider; }
  public String getModelName() { return modelName; }
  public String getApiKey() { return apiKey; }
  public int getMaxTokens() { return maxTokens; }
  public double getTemperature() { return temperature; }
  public String getAnthropicVersion() { return anthropicVersion; }
}
