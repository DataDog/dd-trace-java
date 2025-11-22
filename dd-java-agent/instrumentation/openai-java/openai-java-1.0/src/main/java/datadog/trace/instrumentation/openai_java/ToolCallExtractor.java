package datadog.trace.instrumentation.openai_java;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import datadog.trace.api.llmobs.LLMObs;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ToolCallExtractor {
  private static final Logger log = LoggerFactory.getLogger(ToolCallExtractor.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE_REF =
      new TypeReference<Map<String, Object>>() {};

  // TODO add support for v3+
  public static LLMObs.ToolCall getToolCall(ChatCompletionMessageToolCall toolCall) {
    try {
      String toolId = toolCall.id();
      ChatCompletionMessageToolCall.Function function = toolCall.function();
      String name = function.name();
      String argumentsJson = function.arguments();

      Map<String, Object> arguments = Collections.singletonMap("value", argumentsJson);
      try {
        arguments = MAPPER.readValue(argumentsJson, MAP_TYPE_REF);
      } catch (Exception e) {
        log.debug("Failed to parse tool call arguments as JSON: {}", argumentsJson, e);
      }

      String type = "function";
      Optional<String> typeOpt = toolCall._type().asString();
      if (typeOpt.isPresent()) {
        type = typeOpt.get();
      }

      return LLMObs.ToolCall.from(name, type, toolId, arguments);
    } catch (Exception e) {
      log.debug("Failed to extract tool call information", e);
    }
    return null;
  }
}
