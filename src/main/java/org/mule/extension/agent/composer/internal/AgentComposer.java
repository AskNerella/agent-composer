// Copyright 2023 K2 Partnering Solutions, Inc. All rights reserved.
package org.mule.extension.agent.composer.internal;

import org.mule.sdk.api.annotation.Extension;

import static org.mule.sdk.api.meta.JavaVersion.JAVA_17;

import org.mule.sdk.api.annotation.Configurations;
import org.mule.sdk.api.annotation.dsl.xml.Xml;
import org.mule.sdk.api.annotation.JavaVersionSupport;


@Xml(prefix = "agent-composer")
@Extension(name = "Agent Composer")
@Configurations(AgentComposerConfiguration.class)
@JavaVersionSupport({JAVA_17})
public class AgentComposer {

    public AgentComposer() {
        
    }

}
