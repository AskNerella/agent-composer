package org.mule.extension.agent.composer.internal.error;

import org.mule.sdk.api.error.ErrorTypeDefinition;
import org.mule.sdk.api.error.MuleErrors;

import java.util.Optional;

public enum AgentComposerErrors implements ErrorTypeDefinition<AgentComposerErrors> {

    UNABLE_TO_FETCH_TOOLS,
    AGENT_LISTENER_ERROR;

    @Override
    public Optional<ErrorTypeDefinition<? extends Enum<?>>> getParent() {
        return Optional.of(MuleErrors.CONNECTIVITY);
    }
}
