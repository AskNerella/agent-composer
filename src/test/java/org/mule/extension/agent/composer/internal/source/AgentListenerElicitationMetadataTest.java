package org.mule.extension.agent.composer.internal.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

public class AgentListenerElicitationMetadataTest {

    @Test
    public void inputRequiredTaskContainsTextAndStructuredDataParts() throws Exception {
        Method buildTask = AgentListenerSource.class.getDeclaredMethod("buildTask",
                String.class, String.class, String.class, String.class, String.class, String.class);
        buildTask.setAccessible(true);
        JsonObject task = (JsonObject) buildTask.invoke(null,
                "task-1", "ctx-1", "input-required", "Which cabin?", null, null);

        JsonObject response = new JsonObject();
        response.addProperty("inputKind", "elicitation");
        response.addProperty("elicitationServerName", "travel");
        response.addProperty("elicitationToolName", "book_trip");
        response.addProperty("executionId", "execution-1");
        response.addProperty("traceReportPath", "/agent/traces?executionId=execution-1");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        response.add("elicitationSchema", schema);

        Method addMetadata = AgentListenerSource.class.getDeclaredMethod(
                "addInputRequiredMetadata", JsonObject.class, JsonObject.class);
        addMetadata.setAccessible(true);
        addMetadata.invoke(null, task, response);

        JsonArray parts = task.getAsJsonObject("status").getAsJsonObject("message").getAsJsonArray("parts");
        assertEquals(2, parts.size());
        assertEquals("text", parts.get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals("data", parts.get(1).getAsJsonObject().get("kind").getAsString());
        assertEquals("travel", parts.get(1).getAsJsonObject().getAsJsonObject("data")
                .get("elicitationServerName").getAsString());
        assertEquals("execution-1", parts.get(1).getAsJsonObject().getAsJsonObject("data")
                .get("executionId").getAsString());
    }
}
