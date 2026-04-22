package org.mule.extension.agent.composer.internal.error;

import org.mule.sdk.api.annotation.error.ErrorTypeProvider;
import org.mule.sdk.api.error.ErrorTypeDefinition;

import java.util.Set;

public class AgentComposerErrorTypeProvider implements ErrorTypeProvider {

    @Override
    public Set<ErrorTypeDefinition> getErrorTypes() {
        return Set.of(AgentComposerErrors.UNABLE_TO_FETCH_TOOLS, AgentComposerErrors.AGENT_LISTENER_ERROR);
    }
}
