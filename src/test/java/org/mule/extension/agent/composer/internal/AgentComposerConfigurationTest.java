package org.mule.extension.agent.composer.internal;

import org.junit.Test;
import org.mule.extension.agent.composer.internal.configs.AnthropicProviderConfig;
import org.mule.extension.agent.composer.internal.configs.CustomLlmProviderConfig;
import org.mule.extension.agent.composer.internal.configs.LlmProviderConfig;
import org.mule.extension.agent.composer.internal.configs.OpenAiProviderConfig;
import org.mule.extension.agent.composer.internal.enums.LlmProvider;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.values.OfValues;
import org.mule.sdk.api.annotation.SubTypeMapping;
import org.mule.sdk.api.annotation.param.Parameter;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.Assert.*;

public class AgentComposerConfigurationTest {

    @Test
    public void exposesProviderSpecificSubtypesAndRequestedDefaults() throws Exception {
        SubTypeMapping mapping = AgentComposer.class.getAnnotation(SubTypeMapping.class);
        assertNotNull(mapping);
        assertEquals(LlmProviderConfig.class, mapping.baseType());
        assertTrue(Arrays.asList(mapping.subTypes()).contains(OpenAiProviderConfig.class));
        assertTrue(Arrays.asList(mapping.subTypes()).contains(AnthropicProviderConfig.class));
        assertTrue(Arrays.asList(mapping.subTypes()).contains(CustomLlmProviderConfig.class));

        assertEquals("1.0", optionalDefault("temperature"));
        assertEquals("false", optionalDefault("includeMcpToolsAsSkills"));

        assertNull(field("provider").getAnnotation(Parameter.class));
        assertNull(field("modelName").getAnnotation(Parameter.class));
        assertNull(field("anthropicVersion").getAnnotation(Parameter.class));
        assertNull(field("apiKey").getAnnotation(Parameter.class));
        assertNull(field("customLlmEndpointUrl").getAnnotation(Parameter.class));
        assertNull(field("customLlmHeaders").getAnnotation(Parameter.class));
        assertNotNull(field("llmProvider").getAnnotation(Parameter.class));
        assertNull(field("llmProvider").getAnnotation(Optional.class));

        Field openAiModel = OpenAiProviderConfig.class.getDeclaredField("modelName");
        OfValues modelValues = openAiModel.getAnnotation(OfValues.class);
        assertNotNull(modelValues);
        assertTrue(modelValues.open());
        assertNull(OpenAiProviderConfig.class.getDeclaredField("apiKey").getAnnotation(Optional.class));
        assertNull(AnthropicProviderConfig.class.getDeclaredField("apiKey").getAnnotation(Optional.class));

        AgentComposerConfiguration config = new AgentComposerConfiguration();
        OpenAiProviderConfig openAi = new OpenAiProviderConfig();
        set(openAi, "modelName", "gpt-test");
        set(openAi, "apiKey", "sk-test");
        set(config, "llmProvider", openAi);
        assertEquals(LlmProvider.OPENAI, config.getProvider());
        assertEquals("gpt-test", config.getModelName());
        assertEquals("sk-test", config.getApiKey());
    }

    private static String optionalDefault(String name) throws Exception {
        Optional optional = field(name).getAnnotation(Optional.class);
        assertNotNull(optional);
        return optional.defaultValue();
    }

    private static Field field(String name) throws Exception {
        return AgentComposerConfiguration.class.getDeclaredField(name);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
