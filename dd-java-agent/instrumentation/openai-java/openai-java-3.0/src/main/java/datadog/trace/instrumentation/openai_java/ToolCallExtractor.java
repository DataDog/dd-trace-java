package datadog.trace.instrumentation.openai_java;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.responses.ResponseFunctionToolCall;
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

      String type = "function";
      Optional<String> typeOpt = functionToolCall._type().asString();
      if (typeOpt.isPresent()) {
        type = typeOpt.get();
      }

      Map<String, Object> arguments = parseArguments(argumentsJson);
      return LLMObs.ToolCall.from(name, type, toolId, arguments);
    } catch (Exception e) {
      log.debug("Failed to extract tool call information", e);
    }
    return null;
  }

  public static LLMObs.ToolCall getToolCall(ResponseFunctionToolCall functionCall) {
    try {
      String name = functionCall.name();
      String callId = functionCall.callId();
      String argumentsJson = functionCall.arguments();

      String type = "function_call";
      Optional<String> typeOpt = functionCall._type().asString();
      if (typeOpt.isPresent()) {
        type = typeOpt.get();
      }

      Map<String, Object> arguments = parseArguments(argumentsJson);
      return LLMObs.ToolCall.from(name, type, callId, arguments);
    } catch (Exception e) {
      log.debug("Failed to extract tool call information", e);
    }
    return null;
  }

  private static Map<String, Object> parseArguments(String argumentsJson) {
    try {
      return MAPPER.readValue(argumentsJson, MAP_TYPE_REF);
    } catch (Exception e) {
      log.debug("Failed to parse tool call arguments as JSON: {}", argumentsJson, e);
      return Collections.singletonMap("value", argumentsJson);
    }
  }
}
