package datadog.trace.instrumentation.openai_java;

import com.openai.core.JsonField;
import com.openai.core.JsonValue;
import com.openai.models.Reasoning;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseStreamEvent;
import datadog.json.JsonWriter;
import datadog.trace.api.Config;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ResponseDecorator {
  public static final ResponseDecorator DECORATE = new ResponseDecorator();

  private static final CharSequence RESPONSES_CREATE = UTF8BytesString.create("createResponse");

  private final boolean llmObsEnabled = Config.get().isLlmObsEnabled();

  public void withResponseCreateParams(AgentSpan span, ResponseCreateParams params) {
    span.setResourceName(RESPONSES_CREATE);
    span.setTag(CommonTags.OPENAI_REQUEST_ENDPOINT, "/v1/responses");
    span.setTag(CommonTags.OPENAI_REQUEST_METHOD, "POST");
    if (!llmObsEnabled) {
      return;
    }

    span.setTag(CommonTags.SPAN_KIND, Tags.LLMOBS_LLM_SPAN_KIND);
    if (params == null) {
      return;
    }
    // Use ResponseCreateParams._model() b/o ResponseCreateParams.model() changed type from
    // ResponsesModel to Optional<ResponsesModel> in
    // https://github.com/openai/openai-java/commit/87dd64658da6cec7564f3b571e15ec0e2db0660b
    String modelName = extractResponseModel(params._model());
    span.setTag(CommonTags.OPENAI_REQUEST_MODEL, modelName);

    List<LLMObs.LLMMessage> inputMessages = new ArrayList<>();

    params
        .instructions()
        .ifPresent(
            instructions -> {
              inputMessages.add(LLMObs.LLMMessage.from("system", instructions));
            });

    Optional<String> textOpt = params._input().asString();
    if (textOpt.isPresent()) {
      inputMessages.add(LLMObs.LLMMessage.from("user", textOpt.get()));
    }

    Optional<ResponseCreateParams.Input> inputOpt = params._input().asKnown();
    if (inputOpt.isPresent()) {
      ResponseCreateParams.Input input = inputOpt.get();
      if (input.isText()) {
        inputMessages.add(LLMObs.LLMMessage.from("user", input.asText()));
      } else if (input.isResponse()) {
        List<ResponseInputItem> inputItems = input.asResponse();
        for (ResponseInputItem item : inputItems) {
          LLMObs.LLMMessage message = extractInputItemMessage(item);
          if (message != null) {
            inputMessages.add(message);
          }
        }
      }
    }

    // Handle raw list input (when SDK can't parse into known types)
    // This path is tested by "create streaming response with raw json tool input test"
    if (inputMessages.isEmpty()) {
      try {
        Optional<JsonValue> rawValueOpt = params._input().asUnknown();
        if (rawValueOpt.isPresent()) {
          JsonValue rawValue = rawValueOpt.get();
          Optional<List<JsonValue>> rawListOpt = rawValue.asArray();
          if (rawListOpt.isPresent()) {
            for (JsonValue item : rawListOpt.get()) {
              LLMObs.LLMMessage message = extractMessageFromRawJson(item);
              if (message != null) {
                inputMessages.add(message);
              }
            }
          }
        }
      } catch (Exception e) {
        // Ignore parsing errors for raw input
      }
    }

    if (!inputMessages.isEmpty()) {
      span.setTag(CommonTags.INPUT, inputMessages);
    }

    extractReasoningFromParams(params)
        .ifPresent(reasoningMap -> span.setTag(CommonTags.REQUEST_REASONING, reasoningMap));
  }

  private LLMObs.LLMMessage extractInputItemMessage(ResponseInputItem item) {
    if (item.isMessage()) {
      ResponseInputItem.Message message = item.asMessage();
      String role = message.role().asString();
      String content = extractInputMessageContent(message);
      return LLMObs.LLMMessage.from(role, content);
    } else if (item.isFunctionCall()) {
      // Function call is mapped to assistant message with tool_calls
      ResponseFunctionToolCall functionCall = item.asFunctionCall();
      LLMObs.ToolCall toolCall = ToolCallExtractor.getToolCall(functionCall);
      if (toolCall != null) {
        List<LLMObs.ToolCall> toolCalls = Collections.singletonList(toolCall);
        return LLMObs.LLMMessage.from("assistant", null, toolCalls);
      }
    } else if (item.isFunctionCallOutput()) {
      ResponseInputItem.FunctionCallOutput output = item.asFunctionCallOutput();
      String callId = output.callId();
      String result = FunctionCallOutputExtractor.getOutputAsString(output);
      LLMObs.ToolResult toolResult =
          LLMObs.ToolResult.from("", "function_call_output", callId, result);
      List<LLMObs.ToolResult> toolResults = Collections.singletonList(toolResult);
      return LLMObs.LLMMessage.fromToolResults("user", toolResults);
    }
    return null;
  }

  private LLMObs.LLMMessage extractMessageFromRawJson(JsonValue jsonValue) {
    Optional<Map<String, JsonValue>> objOpt = jsonValue.asObject();
    if (!objOpt.isPresent()) {
      return null;
    }

    Map<String, JsonValue> obj = objOpt.get();
    JsonValue typeValue = obj.get("type");

    // Check if it's a function_call
    if (typeValue != null) {
      Optional<String> typeStr = typeValue.asString();
      if (typeStr.isPresent()) {
        String type = typeStr.get();

        if ("function_call".equals(type)) {
          // Extract function call details
          JsonValue callIdValue = obj.get("call_id");
          JsonValue nameValue = obj.get("name");
          JsonValue argumentsValue = obj.get("arguments");

          String callId = null;
          String name = null;
          String argumentsStr = null;

          if (callIdValue != null) {
            Optional<String> opt = callIdValue.asString();
            if (opt.isPresent()) {
              callId = opt.get();
            }
          }
          if (nameValue != null) {
            Optional<String> opt = nameValue.asString();
            if (opt.isPresent()) {
              name = opt.get();
            }
          }
          if (argumentsValue != null) {
            Optional<String> opt = argumentsValue.asString();
            if (opt.isPresent()) {
              argumentsStr = opt.get();
            }
          }

          if (callId != null && name != null && argumentsStr != null) {
            Map<String, Object> arguments = parseJsonString(argumentsStr);
            LLMObs.ToolCall toolCall =
                LLMObs.ToolCall.from(name, "function_call", callId, arguments);
            return LLMObs.LLMMessage.from("assistant", null, Collections.singletonList(toolCall));
          }
        } else if ("function_call_output".equals(type)) {
          // Extract function call output
          JsonValue callIdValue = obj.get("call_id");
          JsonValue outputValue = obj.get("output");

          String callId = null;
          String output = null;

          if (callIdValue != null) {
            Optional<String> opt = callIdValue.asString();
            if (opt.isPresent()) {
              callId = opt.get();
            }
          }
          if (outputValue != null) {
            Optional<String> opt = outputValue.asString();
            if (opt.isPresent()) {
              output = opt.get();
            }
          }

          if (callId != null && output != null) {
            LLMObs.ToolResult toolResult =
                LLMObs.ToolResult.from("", "function_call_output", callId, output);
            return LLMObs.LLMMessage.fromToolResults("user", Collections.singletonList(toolResult));
          }
        }
      }
    }

    // Otherwise, it's a regular message with role and content
    JsonValue roleValue = obj.get("role");
    JsonValue contentValue = obj.get("content");

    String role = null;
    String content = null;

    if (roleValue != null) {
      Optional<String> opt = roleValue.asString();
      if (opt.isPresent()) {
        role = opt.get();
      }
    }
    if (contentValue != null) {
      Optional<String> opt = contentValue.asString();
      if (opt.isPresent()) {
        content = opt.get();
      }
    }

    if (role != null) {
      return LLMObs.LLMMessage.from(role, content);
    }

    return null;
  }

  private Map<String, Object> parseJsonString(String jsonStr) {
    if (jsonStr == null || jsonStr.isEmpty()) {
      return Collections.emptyMap();
    }
    try {
      jsonStr = jsonStr.trim();
      if (!jsonStr.startsWith("{") || !jsonStr.endsWith("}")) {
        return Collections.emptyMap();
      }

      Map<String, Object> result = new HashMap<>();
      String content = jsonStr.substring(1, jsonStr.length() - 1).trim();

      if (content.isEmpty()) {
        return result;
      }

      // Parse JSON manually, respecting quoted strings
      List<String> pairs = splitByCommaRespectingQuotes(content);

      for (String pair : pairs) {
        int colonIdx = pair.indexOf(':');
        if (colonIdx > 0) {
          String key = pair.substring(0, colonIdx).trim();
          String value = pair.substring(colonIdx + 1).trim();

          // Remove quotes from key
          key = removeQuotes(key);
          // Remove quotes from value
          value = removeQuotes(value);

          result.put(key, value);
        }
      }

      return result;
    } catch (Exception e) {
      return Collections.emptyMap();
    }
  }

  private List<String> splitByCommaRespectingQuotes(String str) {
    List<String> result = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;

    for (int i = 0; i < str.length(); i++) {
      char c = str.charAt(i);

      if (c == '"') {
        inQuotes = !inQuotes;
        current.append(c);
      } else if (c == ',' && !inQuotes) {
        result.add(current.toString());
        current = new StringBuilder();
      } else {
        current.append(c);
      }
    }

    if (current.length() > 0) {
      result.add(current.toString());
    }

    return result;
  }

  private String removeQuotes(String str) {
    str = str.trim();
    if (str.startsWith("\"") && str.endsWith("\"") && str.length() >= 2) {
      return str.substring(1, str.length() - 1);
    }
    return str;
  }

  private String extractInputMessageContent(ResponseInputItem.Message message) {
    StringBuilder contentBuilder = new StringBuilder();
    for (ResponseInputContent content : message.content()) {
      if (content.isInputText()) {
        contentBuilder.append(content.asInputText().text());
      }
    }
    String result = contentBuilder.toString();
    return result.isEmpty() ? null : result;
  }

  private Optional<Map<String, String>> extractReasoningFromParams(ResponseCreateParams params) {
    JsonField<Reasoning> reasoningField = params._reasoning();
    if (reasoningField.isMissing()) {
      return Optional.empty();
    }

    Map<String, String> reasoningMap = new HashMap<>();

    Optional<Reasoning> knownReasoning = reasoningField.asKnown();
    if (knownReasoning.isPresent()) {
      Reasoning reasoning = knownReasoning.get();
      reasoning.effort().ifPresent(effort -> reasoningMap.put("effort", effort.asString()));
      reasoning.summary().ifPresent(summary -> reasoningMap.put("summary", summary.asString()));
    } else {
      Optional<Map<String, JsonValue>> rawObject = reasoningField.asObject();
      if (rawObject.isPresent()) {
        Map<String, JsonValue> obj = rawObject.get();
        JsonValue effortVal = obj.get("effort");
        if (effortVal != null) {
          effortVal.asString().ifPresent(v -> reasoningMap.put("effort", String.valueOf(v)));
        }
        JsonValue summaryVal = obj.get("summary");
        if (summaryVal == null) {
          summaryVal = obj.get("generate_summary");
        }
        if (summaryVal != null) {
          summaryVal.asString().ifPresent(v -> reasoningMap.put("summary", String.valueOf(v)));
        }
      }
    }

    return reasoningMap.isEmpty() ? Optional.empty() : Optional.of(reasoningMap);
  }

  public void withResponse(AgentSpan span, Response response) {
    withResponse(span, response, false);
  }

  public void withResponseStreamEvents(AgentSpan span, List<ResponseStreamEvent> events) {
    if (!llmObsEnabled) {
      return;
    }

    for (ResponseStreamEvent event : events) {
      if (event.isCompleted()) {
        Response response = event.asCompleted().response();
        withResponse(span, response, true);
        return;
      }
      if (event.isIncomplete()) {
        Response response = event.asIncomplete().response();
        withResponse(span, response, true);
        return;
      }
    }
  }

  private void withResponse(AgentSpan span, Response response, boolean stream) {
    if (!llmObsEnabled) {
      return;
    }

    String modelName = extractResponseModel(response._model());
    span.setTag(CommonTags.OPENAI_RESPONSE_MODEL, modelName);
    span.setTag(CommonTags.MODEL_NAME, modelName);
    span.setTag(CommonTags.MODEL_PROVIDER, "openai");

    List<LLMObs.LLMMessage> outputMessages = extractResponseOutputMessages(response.output());
    if (!outputMessages.isEmpty()) {
      span.setTag(CommonTags.OUTPUT, outputMessages);
    }

    Map<String, Object> metadata = new HashMap<>();

    Object reasoningTag = span.getTag(CommonTags.REQUEST_REASONING);
    if (reasoningTag != null) {
      metadata.put("reasoning", reasoningTag);
    }

    response.maxOutputTokens().ifPresent(v -> metadata.put("max_output_tokens", v));
    response.temperature().ifPresent(v -> metadata.put("temperature", v));
    response.topP().ifPresent(v -> metadata.put("top_p", v));

    Response.ToolChoice toolChoice = response.toolChoice();
    if (toolChoice.isOptions()) {
      metadata.put("tool_choice", toolChoice.asOptions()._value().asString().orElse(null));
    } else if (toolChoice.isTypes()) {
      metadata.put("tool_choice", toolChoice.asTypes().type().toString().toLowerCase());
    } else if (toolChoice.isFunction()) {
      metadata.put("tool_choice", "function");
    }

    response
        .truncation()
        .ifPresent(
            (Response.Truncation t) ->
                metadata.put("truncation", t._value().asString().orElse(null)));

    response
        .text()
        .ifPresent(
            textConfig -> {
              textConfig
                  .format()
                  .ifPresent(
                      format -> {
                        Map<String, Object> textMap = new HashMap<>();
                        Map<String, String> formatMap = new HashMap<>();
                        if (format.isText()) {
                          formatMap.put("type", "text");
                        } else if (format.isJsonSchema()) {
                          formatMap.put("type", "json_schema");
                        } else if (format.isJsonObject()) {
                          formatMap.put("type", "json_object");
                        }
                        textMap.put("format", formatMap);
                        metadata.put("text", textMap);
                      });
            });

    if (stream) {
      metadata.put("stream", true);
    }

    span.setTag(CommonTags.METADATA, metadata);

    response
        .usage()
        .ifPresent(
            usage -> {
              span.setTag(CommonTags.INPUT_TOKENS, usage.inputTokens());
              span.setTag(CommonTags.OUTPUT_TOKENS, usage.outputTokens());
              span.setTag(CommonTags.TOTAL_TOKENS, usage.totalTokens());
              span.setTag(
                  CommonTags.CACHE_READ_INPUT_TOKENS, usage.inputTokensDetails().cachedTokens());
              span.setTag(
                  CommonTags.REASONING_OUTPUT_TOKENS,
                  usage.outputTokensDetails().reasoningTokens());
            });
  }

  private List<LLMObs.LLMMessage> extractResponseOutputMessages(List<ResponseOutputItem> output) {
    List<LLMObs.LLMMessage> messages = new ArrayList<>();

    for (ResponseOutputItem item : output) {
      if (item.isFunctionCall()) {
        ResponseFunctionToolCall functionCall = item.asFunctionCall();
        LLMObs.ToolCall toolCall = ToolCallExtractor.getToolCall(functionCall);
        if (toolCall != null) {
          List<LLMObs.ToolCall> toolCalls = Collections.singletonList(toolCall);
          messages.add(LLMObs.LLMMessage.from("assistant", null, toolCalls));
        }
      } else if (item.isMessage()) {
        ResponseOutputMessage message = item.asMessage();
        String textContent = extractMessageContent(message);
        Optional<String> roleOpt = message._role().asString();
        String role = roleOpt.orElse("assistant");
        messages.add(LLMObs.LLMMessage.from(role, textContent));
      } else if (item.isReasoning()) {
        ResponseReasoningItem reasoning = item.asReasoning();
        try (JsonWriter writer = new JsonWriter()) {
          writer.beginObject();
          if (!reasoning.summary().isEmpty()) {
            writer.name("summary").value(reasoning.summary().get(0).text());
          }
          reasoning.encryptedContent().ifPresent(v -> writer.name("encrypted_content").value(v));
          writer.name("id").value(reasoning.id());
          writer.endObject();
          messages.add(LLMObs.LLMMessage.from("reasoning", writer.toString()));
        }
      }
    }
    return messages;
  }

  private String extractMessageContent(ResponseOutputMessage message) {
    StringBuilder contentBuilder = new StringBuilder();
    for (ResponseOutputMessage.Content content : message.content()) {
      if (content.isOutputText()) {
        ResponseOutputText outputText = content.asOutputText();
        contentBuilder.append(outputText.text());
      }
    }
    String result = contentBuilder.toString();
    return result.isEmpty() ? null : result;
  }

  private String extractResponseModel(JsonField<ResponsesModel> model) {
    Optional<String> str = model.asString();
    if (str.isPresent()) {
      return str.get();
    }
    Optional<ResponsesModel> known = model.asKnown();
    if (known.isPresent()) {
      ResponsesModel m = known.get();
      if (m.isString()) {
        return m.asString();
      }
      if (m.isChat()) {
        Optional<String> s = m.asChat()._value().asString();
        if (s.isPresent()) {
          return s.get();
        }
      }
      if (m.isOnly()) {
        Optional<String> s = m.asOnly()._value().asString();
        if (s.isPresent()) {
          return s.get();
        }
      }
    }
    return null;
  }
}
