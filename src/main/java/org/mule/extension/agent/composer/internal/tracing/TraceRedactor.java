package org.mule.extension.agent.composer.internal.tracing;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Redacts credentials before trace data reaches persistence, JSON, or HTML. */
public final class TraceRedactor {
    private static final Gson GSON = new Gson();
    private static final String REDACTED = "[REDACTED]";
    private static final int MAX_STRING_LENGTH = 100_000;
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[a-z0-9._~+\\-/=]+");
    private static final Pattern OPENAI_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{12,}\\b");

    private TraceRedactor() {}

    public static Object redact(Object value) {
        if (value == null) return null;
        JsonElement redacted = redactElement(GSON.toJsonTree(value), null);
        return GSON.fromJson(redacted, Object.class);
    }

    public static String toRedactedJson(Object value) {
        if (value == null) return "null";
        return GSON.toJson(redactElement(GSON.toJsonTree(value), null));
    }

    public static String redactText(String value) {
        if (value == null) return null;
        String result = BEARER.matcher(value).replaceAll("Bearer " + REDACTED);
        result = OPENAI_KEY.matcher(result).replaceAll(REDACTED);
        if (result.length() > MAX_STRING_LENGTH) {
            return result.substring(0, MAX_STRING_LENGTH) + "… [TRUNCATED]";
        }
        return result;
    }

    private static JsonElement redactElement(JsonElement element, String key) {
        if (element == null || element.isJsonNull()) return element;
        if (isSensitiveKey(key)) return new JsonPrimitive(REDACTED);
        if (element.isJsonObject()) {
            JsonObject output = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                output.add(entry.getKey(), redactElement(entry.getValue(), entry.getKey()));
            }
            return output;
        }
        if (element.isJsonArray()) {
            JsonArray output = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) output.add(redactElement(child, key));
            return output;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return new JsonPrimitive(redactText(element.getAsString()));
        }
        return element.deepCopy();
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("apikey")
                || normalized.contains("accesstoken")
                || normalized.contains("refreshtoken")
                || normalized.contains("bearertoken")
                || normalized.contains("authorization")
                || normalized.contains("clientsecret")
                || normalized.contains("password")
                || normalized.contains("credential")
                || normalized.equals("secret")
                || normalized.equals("token")
                || normalized.contains("cookie");
    }
}
