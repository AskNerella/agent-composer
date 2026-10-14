package org.mule.extension.agent.composer.internal.engine;

import org.junit.Test;
import org.mule.extension.agent.composer.internal.AgentComposerConfiguration;
import org.mule.extension.agent.composer.internal.enums.LlmProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class ReactEngineElicitationInputTest {

    @Test
    public void acceptsBareObjectAndExplicitActions() throws Exception {
        ReactEngine engine = new ReactEngine(null);
        Object bare = parse(engine, schema("cabin", "string"), "{\"cabin\":\"economy\"}");
        assertEquals("accept", field(bare, "action"));
        assertNull(field(bare, "error"));

        Object declined = parse(engine, schema("cabin", "string"), "{\"action\":\"decline\"}");
        assertEquals("decline", field(declined, "action"));
        assertNull(field(declined, "error"));
    }

    @Test
    public void coercesSingleFieldPlainTextAndRetainsValidationErrors() throws Exception {
        ReactEngine engine = new ReactEngine(null);
        Object integer = parse(engine, schema("count", "integer"), "42");
        Map<?, ?> content = (Map<?, ?>) field(integer, "content");
        assertEquals(42L, content.get("count"));

        Map<String, Object> schema = schema("choice", "string");
        ((Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get("choice"))
                .put("enum", Arrays.asList("yes", "no"));
        Object invalid = parse(engine, schema, "maybe");
        assertTrue(String.valueOf(field(invalid, "error")).contains("allowed values"));
    }

    @Test
    public void iterationBudgetIsPartOfSolvedResponseCacheIdentity() throws Exception {
        ReactEngine engine = new ReactEngine(null);
        AgentComposerConfiguration config = new AgentComposerConfiguration();
        setField(config, "provider", LlmProvider.OPENAI);
        setField(config, "modelName", "test-model");

        Method method = ReactEngine.class.getDeclaredMethod("buildRequestSignature",
                AgentComposerConfiguration.class, String.class, java.util.List.class, int.class);
        method.setAccessible(true);

        String fiveIterations = (String) method.invoke(engine, config, "same request",
                Collections.emptyList(), 5);
        String twentyIterations = (String) method.invoke(engine, config, "same request",
                Collections.emptyList(), 20);

        assertNotEquals(fiveIterations, twentyIterations);
        assertTrue(twentyIterations.contains("maxIterations=20"));
    }

    private static Object parse(ReactEngine engine, Map<String, Object> schema, String input) throws Exception {
        Method method = ReactEngine.class.getDeclaredMethod("parseElicitationAnswer", Map.class, String.class);
        method.setAccessible(true);
        return method.invoke(engine, schema, input);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Map<String, Object> schema(String field, String type) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("type", type);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(field, definition);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.singletonList(field));
        return schema;
    }
}
