package org.mule.extension.agent.composer.internal.llm;

import org.mule.extension.agent.composer.internal.model.LlmMessage;
import org.mule.extension.agent.composer.internal.model.LlmResponse;
import org.mule.extension.agent.composer.internal.model.ToolDefinition;

import java.util.List;

/**
 * Abstraction over inference-provider APIs.
 *
 * Implementations are responsible for:
 * <ul>
 *   <li>Serialising the conversation history and tool definitions into the
 *       provider-specific request format.</li>
 *   <li>Deserialising the raw HTTP response into a normalised
 *       {@link LlmResponse}.</li>
 * </ul>
 */
public interface LlmClient {

    /**
     * Sends the conversation history to the LLM and returns the next response.
     *
     * @param systemPrompt the system / instructions message
     * @param messages     current conversation history (mutated externally by the ReAct engine)
     * @param tools        tool definitions available for this turn (already filtered)
     * @return normalised {@link LlmResponse}
     * @throws Exception on HTTP or parsing errors
     */
    LlmResponse chat(String systemPrompt,
                     List<LlmMessage> messages,
                     List<ToolDefinition> tools) throws Exception;
}
