package org.mule.extension.agent.composer.internal.configs;

import org.mule.extension.agent.composer.internal.enums.LlmProvider;

import java.util.Collections;
import java.util.List;

/** Provider-specific LLM settings exposed as a Studio subtype selector. */
public interface LlmProviderConfig {

    LlmProvider getProvider();

    default String getModelName() { return "gpt-4o"; }

    default String getApiKey() { return null; }

    default String getAnthropicVersion() { return "2023-06-01"; }

    default String getEndpointUrl() { return null; }

    default List<LlmHttpHeader> getHeaders() { return Collections.emptyList(); }
}
