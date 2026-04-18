package org.mule.extension.agent.composer.internal.values;

import org.mule.extension.agent.composer.internal.enums.LlmProvider;
import org.mule.runtime.extension.api.annotation.values.OfValues;
import org.mule.runtime.extension.api.values.ValueBuilder;
import org.mule.runtime.extension.api.values.ValueProvider;
import org.mule.runtime.extension.api.values.ValueResolvingException;
import org.mule.sdk.api.annotation.param.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.mule.runtime.api.value.Value;

public class ModelNameValueProvider implements ValueProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelNameValueProvider.class);
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Parameter
    private LlmProvider provider;

    @Parameter
    private String apiKey;

    @Parameter
    private String anthropicVersion;

    @Override
    public Set<Value> resolve() throws ValueResolvingException {
        Set<Value> values = new LinkedHashSet<>();
        try {
            List<String> models = (provider == LlmProvider.ANTHROPIC)
                    ? fetchAnthropicModels()
                    : fetchOpenAiModels();
            for (String model : models) {
                values.add(ValueBuilder.newValue(model).build());
            }
        } catch (Exception e) {
            LOGGER.warn("Could not fetch models from provider {}: {}", provider, e.getMessage());
        }
        return values;
    }

    private List<String> fetchOpenAiModels() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/models"))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray data = root.getAsJsonArray("data");
        List<String> models = new ArrayList<>();
        if (data != null) {
            for (JsonElement el : data) {
                models.add(el.getAsJsonObject().get("id").getAsString());
            }
            models.sort(String::compareTo);
        }
        return models;
    }

    private List<String> fetchAnthropicModels() throws Exception {
        String version = (anthropicVersion != null && !anthropicVersion.isEmpty())
                ? anthropicVersion : "2023-06-01";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/models"))
                .header("x-api-key", apiKey)
                .header("anthropic-version", version)
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray data = root.getAsJsonArray("data");
        List<String> models = new ArrayList<>();
        if (data != null) {
            for (JsonElement el : data) {
                models.add(el.getAsJsonObject().get("id").getAsString());
            }
        }
        return models;
    }
}
