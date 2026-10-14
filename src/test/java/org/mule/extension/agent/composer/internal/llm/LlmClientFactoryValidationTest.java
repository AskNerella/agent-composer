package org.mule.extension.agent.composer.internal.llm;

import org.junit.Test;
import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.enums.LlmProvider;

import java.lang.reflect.Field;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LlmClientFactoryValidationTest {

    @Test
    public void rejectsMissingProvider() {
        expectError(new AgentComposerConfiguration(), "LLM Provider is required");
    }

    @Test
    public void rejectsMissingOpenAiApiKey() throws Exception {
        AgentComposerConfiguration config = new AgentComposerConfiguration();
        set(config, "provider", LlmProvider.OPENAI);
        expectError(config, "API Key is required for OPENAI");
    }

    private static void expectError(AgentComposerConfiguration config, String expected) {
        try {
            LlmClientFactory.create(config);
            fail("Expected configuration validation to fail");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains(expected));
        }
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
