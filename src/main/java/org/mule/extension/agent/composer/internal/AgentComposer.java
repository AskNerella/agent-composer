// Copyright 2023 K2 Partnering Solutions, Inc. All rights reserved.
package org.mule.extension.agent.composer.internal;

import org.mule.extension.agent.composer.internal.error.AgentComposerErrors;
import org.mule.sdk.api.annotation.Extension;
import org.mule.sdk.api.annotation.SubTypeMapping;
import org.mule.extension.agent.composer.internal.configs.AnthropicProviderConfig;
import org.mule.extension.agent.composer.internal.configs.CustomLlmProviderConfig;
import org.mule.extension.agent.composer.internal.configs.LlmProviderConfig;
import org.mule.extension.agent.composer.internal.configs.OpenAiProviderConfig;

import static org.mule.sdk.api.meta.JavaVersion.JAVA_17;

import org.mule.sdk.api.annotation.Configurations;
import org.mule.sdk.api.annotation.dsl.xml.Xml;
import org.mule.sdk.api.annotation.JavaVersionSupport;
import org.mule.sdk.api.annotation.error.ErrorTypes;


@Xml(prefix = "agent-composer")
@Extension(name = "Agent Composer")
@Configurations(AgentComposerConfiguration.class)
@SubTypeMapping(baseType = LlmProviderConfig.class, subTypes = {
        OpenAiProviderConfig.class,
        AnthropicProviderConfig.class,
        CustomLlmProviderConfig.class
})
@JavaVersionSupport({JAVA_17})
@ErrorTypes(AgentComposerErrors.class)
public class AgentComposer {

    public AgentComposer() {
        
    }

}
