package datadog.trace.instrumentation.openai_java;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
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

  public static LLMObs.ToolCall getToolCall(ChatCompletionMessageToolCall toolCall) {
    Optional<ChatCompletionMessageFunctionToolCall> functionToolCallOpt = toolCall.function();
    if (!functionToolCallOpt.isPresent()) {
      return null;
    }
    try {
      ChatCompletionMessageFunctionToolCall functionToolCall = functionToolCallOpt.get();
      String toolId = functionToolCall.id();
      ChatCompletionMessageFunctionToolCall.Function function = functionToolCall.function();
      String name = function.name();
      String argumentsJson = function.arguments();

      Map<String, Object> arguments;
      try {
        arguments = parseArguments(argumentsJson);
      } catch (Exception e) {
        log.debug("Failed to parse tool call arguments as JSON: {}", argumentsJson, e);
        arguments = Collections.singletonMap("value", argumentsJson);
      }

      String type = "function";
      Optional<String> typeOpt = functionToolCall._type().asString();
      if (typeOpt.isPresent()) {
        type = typeOpt.get();
      }

      return LLMObs.ToolCall.from(name, type, toolId, arguments);
    } catch (Exception e) {
      log.debug("Failed to extract tool call information", e);
    }
    return null;
  }

  public static Map<String, Object> parseArguments(String argumentsJson) throws Exception {
    return MAPPER.readValue(argumentsJson, MAP_TYPE_REF);
  }
}
