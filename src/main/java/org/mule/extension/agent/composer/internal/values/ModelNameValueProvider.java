package org.mule.extension.agent.composer.internal.values;

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
    private String apiKey;

    @Override
    public Set<Value> resolve() throws ValueResolvingException {
        Set<Value> values = new LinkedHashSet<>();
        // Property placeholders and expressions remain valid model values. Studio may
        // ask for suggestions before it can resolve the API key, in which case the
        // editable field is left alone instead of issuing an invalid request.
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("${") || apiKey.startsWith("#[")) {
            return values;
        }
        try {
            List<String> models = fetchOpenAiModels();
            for (String model : models) {
                values.add(ValueBuilder.newValue(model).build());
            }
        } catch (Exception e) {
            LOGGER.warn("Could not fetch OpenAI models: {}", e.getMessage());
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

}
