package datadog.trace.instrumentation.openai_java;

import com.openai.core.JsonField;
import com.openai.core.JsonValue;
import com.openai.models.Reasoning;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseCustomToolCall;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponsePrompt;
import datadog.json.JsonWriter;
import datadog.trace.api.Config;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ResponseDecorator {
  public static final ResponseDecorator DECORATE = new ResponseDecorator();

  private static final CharSequence RESPONSES_CREATE = UTF8BytesString.create("createResponse");
  private static final String IMAGE_FALLBACK_MARKER = "[image]";
  private static final String FILE_FALLBACK_MARKER = "[file]";

  private final boolean llmObsEnabled = Config.get().isLlmObsEnabled();

  public void withResponseCreateParams(AgentSpan span, ResponseCreateParams params) {
    span.setResourceName(RESPONSES_CREATE);
    span.setTag(CommonTags.OPENAI_REQUEST_ENDPOINT, "/v1/responses");
    if (params == null) {
      return;
    }
    // Use ResponseCreateParams._model() b/o ResponseCreateParams.model() changed type from
    // ResponsesModel to Optional<ResponsesModel> in
    // https://github.com/openai/openai-java/commit/87dd64658da6cec7564f3b571e15ec0e2db0660b
    String modelName = extractResponseModel(params._model());
    span.setTag(CommonTags.OPENAI_REQUEST_MODEL, modelName);

    if (!llmObsEnabled) {
      return;
    }

    span.setTag(CommonTags.SPAN_KIND, Tags.LLMOBS_LLM_SPAN_KIND);

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

    extractPromptFromParams(params).ifPresent(prompt -> span.setTag(CommonTags.REQUEST_PROMPT, prompt));
  }

  private LLMObs.LLMMessage extractInputItemMessage(ResponseInputItem item) {
    if (item.isEasyInputMessage()) {
      EasyInputMessage message = item.asEasyInputMessage();
      String role = message.role().asString();
      String content = extractEasyInputMessageContent(message);
      if (content == null || content.isEmpty()) {
        return null;
      }
      return LLMObs.LLMMessage.from(role, content);
    } else if (item.isMessage()) {
      ResponseInputItem.Message message = item.asMessage();
      String role = message.role().asString();
      String content = extractInputMessageContent(message);
      if (content == null || content.isEmpty()) {
        return null;
      }
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

  private String extractEasyInputMessageContent(EasyInputMessage message) {
    if (message.content().isTextInput()) {
      String content = message.content().asTextInput();
      return content == null || content.isEmpty() ? null : content;
    }

    if (message.content().isResponseInputMessageContentList()) {
      StringBuilder contentBuilder = new StringBuilder();
      for (ResponseInputContent content : message.content().asResponseInputMessageContentList()) {
        String contentPart = extractInputContentText(content);
        if (contentPart != null) {
          contentBuilder.append(contentPart);
        }
      }
      String result = contentBuilder.toString();
      return result.isEmpty() ? null : result;
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
      String contentPart = extractInputContentText(content);
      if (contentPart != null) {
        contentBuilder.append(contentPart);
      }
    }
    String result = contentBuilder.toString();
    return result.isEmpty() ? null : result;
  }

  private String extractInputContentText(ResponseInputContent content) {
    if (content.isInputText()) {
      return content.asInputText().text();
    }
    if (content.isInputImage()) {
      return content.asInputImage().imageUrl().orElse(content.asInputImage().fileId().orElse(""));
    }
    if (content.isInputFile()) {
      return content
          .asInputFile()
          .fileUrl()
          .orElse(
              content
                  .asInputFile()
                  .fileId()
                  .orElse(content.asInputFile().filename().orElse(FILE_FALLBACK_MARKER)));
    }
    return null;
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
    String modelName = extractResponseModel(response._model());
    span.setTag(CommonTags.OPENAI_RESPONSE_MODEL, modelName);
    span.setTag(CommonTags.MODEL_NAME, modelName);

    if (!llmObsEnabled) {
      return;
    }

    List<LLMObs.LLMMessage> outputMessages = extractResponseOutputMessages(response.output());
    if (!outputMessages.isEmpty()) {
      span.setTag(CommonTags.OUTPUT, outputMessages);
    }

    enrichInputWithPromptTracking(span, response);

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

    Map<String, Object> textMap = new HashMap<>();
    response
        .text()
        .ifPresent(
            textConfig -> {
              textConfig
                  .format()
                  .ifPresent(
                      format -> {
                        Map<String, String> formatMap = new HashMap<>();
                        if (format.isText()) {
                          formatMap.put("type", "text");
                        } else if (format.isJsonSchema()) {
                          formatMap.put("type", "json_schema");
                        } else if (format.isJsonObject()) {
                          formatMap.put("type", "json_object");
                        }
                        textMap.put("format", formatMap);
                      });
              textConfig
                  .verbosity()
                  .ifPresent(
                      verbosity -> {
                        textMap.put("verbosity", verbosity.asString());
                      });
            });
    if (!textMap.isEmpty()) {
      metadata.put("text", textMap);
    }

    metadata.put("stream", stream);

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

  private void enrichInputWithPromptTracking(AgentSpan span, Response response) {
    Object promptTag = span.getTag(CommonTags.REQUEST_PROMPT);
    if (!(promptTag instanceof Map)) {
      return;
    }

    Map<String, Object> prompt = new LinkedHashMap<>((Map<String, Object>) promptTag);
    Map<String, Object> variables = Collections.emptyMap();
    Object variablesTag = prompt.get("variables");
    if (variablesTag instanceof Map) {
      variables = (Map<String, Object>) variablesTag;
    }

    Map<String, Object> inputMap = new LinkedHashMap<>();
    Object inputTag = span.getTag(CommonTags.INPUT);
    if (inputTag instanceof Map) {
      inputMap.putAll((Map<String, Object>) inputTag);
    }

    List<LLMObs.LLMMessage> inputMessages = extractInputMessagesForPromptTracking(span, response);
    if (!inputMessages.isEmpty()) {
      inputMap.put("messages", inputMessages);
    }

    List<Map<String, String>> chatTemplate = extractChatTemplate(inputMessages, variables);
    if (!chatTemplate.isEmpty()) {
      prompt.put("chat_template", chatTemplate);
    }

    inputMap.put("prompt", prompt);

    span.setTag(CommonTags.INPUT, inputMap);
  }

  private List<Map<String, String>> extractChatTemplate(
      List<LLMObs.LLMMessage> messages, Map<String, Object> variables) {
    Map<String, String> valueToPlaceholder = new LinkedHashMap<>();
    for (Map.Entry<String, Object> variable : variables.entrySet()) {
      if (variable.getValue() == null) {
        continue;
      }
      String valueStr = String.valueOf(variable.getValue());
      if (valueStr.isEmpty()
          || IMAGE_FALLBACK_MARKER.equals(valueStr)
          || FILE_FALLBACK_MARKER.equals(valueStr)) {
        continue;
      }
      valueToPlaceholder.put(valueStr, "{{" + variable.getKey() + "}}");
    }

    List<String> sortedValues = new ArrayList<>(valueToPlaceholder.keySet());
    sortedValues.sort((a, b) -> Integer.compare(b.length(), a.length()));

    List<Map<String, String>> chatTemplate = new ArrayList<>();
    for (LLMObs.LLMMessage message : messages) {
      String role = message.getRole();
      String content = message.getContent();
      if (role == null || role.isEmpty() || content == null || content.isEmpty()) {
        continue;
      }

      String templateContent = content;
      for (String value : sortedValues) {
        templateContent = templateContent.replace(value, valueToPlaceholder.get(value));
      }

      Map<String, String> messageMap = new LinkedHashMap<>();
      messageMap.put("role", role);
      messageMap.put("content", templateContent);
      chatTemplate.add(messageMap);
    }
    return chatTemplate;
  }

  private List<LLMObs.LLMMessage> extractInputMessagesForPromptTracking(
      AgentSpan span, Response response) {
    List<LLMObs.LLMMessage> messages = new ArrayList<>();

    Object inputTag = span.getTag(CommonTags.INPUT);
    if (inputTag instanceof List) {
      for (Object messageObj : (List<?>) inputTag) {
        if (messageObj instanceof LLMObs.LLMMessage) {
          messages.add((LLMObs.LLMMessage) messageObj);
        }
      }
    } else if (inputTag instanceof Map) {
      Object messagesObj = ((Map<?, ?>) inputTag).get("messages");
      if (messagesObj instanceof List) {
        for (Object messageObj : (List<?>) messagesObj) {
          if (messageObj instanceof LLMObs.LLMMessage) {
            messages.add((LLMObs.LLMMessage) messageObj);
          }
        }
      }
    }

    if (!messages.isEmpty()) {
      return messages;
    }

    response
        .instructions()
        .ifPresent(
            instructions -> {
              if (instructions.isInputItemList()) {
                for (ResponseInputItem item : instructions.asInputItemList()) {
                  LLMObs.LLMMessage message = extractInputItemMessage(item);
                  if (message != null) {
                    messages.add(message);
                  }
                }
              } else if (instructions.isString()) {
                String text = instructions.asString();
                if (text != null && !text.isEmpty()) {
                  messages.add(LLMObs.LLMMessage.from("user", text));
                }
              }
            });

    if (!messages.isEmpty()) {
      return messages;
    }

    // Fallback for SDK union parsing mismatches: parse raw instructions payload.
    Optional<JsonValue> rawInstructions = response._instructions().asUnknown();
    if (rawInstructions.isPresent()) {
      Optional<List<JsonValue>> rawList = rawInstructions.get().asArray();
      if (rawList.isPresent()) {
        for (JsonValue item : rawList.get()) {
          LLMObs.LLMMessage message = extractMessageFromRawInstruction(item);
          if (message != null) {
            messages.add(message);
          }
        }
      }
    }

    return messages;
  }

  private LLMObs.LLMMessage extractMessageFromRawInstruction(JsonValue instructionValue) {
    Optional<Map<String, JsonValue>> objOpt = instructionValue.asObject();
    if (!objOpt.isPresent()) {
      return null;
    }
    Map<String, JsonValue> obj = objOpt.get();
    String role = getJsonString(obj.get("role"));
    if (role == null || role.isEmpty()) {
      return null;
    }

    JsonValue contentValue = obj.get("content");
    if (contentValue == null) {
      return null;
    }
    Optional<List<JsonValue>> contentList = contentValue.asArray();
    if (!contentList.isPresent()) {
      return null;
    }

    StringBuilder contentBuilder = new StringBuilder();
    for (JsonValue contentItem : contentList.get()) {
      Optional<Map<String, JsonValue>> contentObjOpt = contentItem.asObject();
      if (!contentObjOpt.isPresent()) {
        continue;
      }
      Map<String, JsonValue> contentObj = contentObjOpt.get();
      String type = getJsonString(contentObj.get("type"));
      if ("input_text".equals(type)) {
        String text = getJsonString(contentObj.get("text"));
        if (text != null) {
          contentBuilder.append(text);
        }
      } else if ("input_image".equals(type)) {
        String imageUrl = getJsonString(contentObj.get("image_url"));
        if (imageUrl != null && !imageUrl.isEmpty()) {
          contentBuilder.append(imageUrl);
        } else {
          String fileId = getJsonString(contentObj.get("file_id"));
          contentBuilder.append(fileId == null || fileId.isEmpty() ? IMAGE_FALLBACK_MARKER : fileId);
        }
      } else if ("input_file".equals(type)) {
        String fileUrl = getJsonString(contentObj.get("file_url"));
        if (fileUrl != null && !fileUrl.isEmpty()) {
          contentBuilder.append(fileUrl);
        } else {
          String fileId = getJsonString(contentObj.get("file_id"));
          if (fileId != null && !fileId.isEmpty()) {
            contentBuilder.append(fileId);
          } else {
            String filename = getJsonString(contentObj.get("filename"));
            contentBuilder.append(
                filename == null || filename.isEmpty() ? FILE_FALLBACK_MARKER : filename);
          }
        }
      }
    }

    String content = contentBuilder.toString();
    if (content.isEmpty()) {
      return null;
    }
    return LLMObs.LLMMessage.from(role, content);
  }

  private Optional<Map<String, Object>> extractPromptFromParams(ResponseCreateParams params) {
    Optional<ResponsePrompt> promptOpt = params.prompt();
    if (!promptOpt.isPresent()) {
      return Optional.empty();
    }

    ResponsePrompt prompt = promptOpt.get();
    Map<String, Object> promptMap = new LinkedHashMap<>();

    String id = prompt.id();
    if (id != null && !id.isEmpty()) {
      promptMap.put("id", id);
    }
    prompt.version().ifPresent(version -> promptMap.put("version", version));
    prompt
        .variables()
        .ifPresent(
            variables -> {
              Map<String, Object> normalized = normalizePromptVariables(variables);
              if (!normalized.isEmpty()) {
                promptMap.put("variables", normalized);
              }
            });

    return promptMap.isEmpty() ? Optional.empty() : Optional.of(promptMap);
  }

  private Map<String, Object> normalizePromptVariables(ResponsePrompt.Variables variables) {
    Map<String, Object> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, JsonValue> entry : variables._additionalProperties().entrySet()) {
      Object value = normalizePromptVariable(entry.getValue());
      if (value != null) {
        normalized.put(entry.getKey(), value);
      }
    }
    return normalized;
  }

  private Object normalizePromptVariable(JsonValue value) {
    if (value == null) {
      return null;
    }

    Optional<String> asString = value.asString();
    if (asString.isPresent()) {
      return asString.get();
    }

    Optional<Map<String, JsonValue>> asObject = value.asObject();
    if (!asObject.isPresent()) {
      return value.toString();
    }

    Map<String, JsonValue> obj = asObject.get();
    String type = getJsonString(obj.get("type"));
    String text = getJsonString(obj.get("text"));
    if (text != null && !text.isEmpty()) {
      return text;
    }

    if ("input_image".equals(type)) {
      String imageUrl = getJsonString(obj.get("image_url"));
      if (imageUrl != null && !imageUrl.isEmpty()) {
        return imageUrl;
      }
      String fileId = getJsonString(obj.get("file_id"));
      return fileId == null || fileId.isEmpty() ? IMAGE_FALLBACK_MARKER : fileId;
    }

    if ("input_file".equals(type)) {
      String fileUrl = getJsonString(obj.get("file_url"));
      if (fileUrl != null && !fileUrl.isEmpty()) {
        return fileUrl;
      }
      String fileId = getJsonString(obj.get("file_id"));
      if (fileId != null && !fileId.isEmpty()) {
        return fileId;
      }
      String filename = getJsonString(obj.get("filename"));
      return filename == null || filename.isEmpty() ? FILE_FALLBACK_MARKER : filename;
    }

    return value.toString();
  }

  private String getJsonString(JsonValue value) {
    if (value == null) {
      return null;
    }
    Optional<String> asString = value.asString();
    return asString.orElse(null);
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
      } else if (item.isCustomToolCall()) {
        ResponseCustomToolCall customToolCall = item.asCustomToolCall();
        LLMObs.ToolCall toolCall = ToolCallExtractor.getToolCall(customToolCall);
        if (toolCall != null) {
          messages.add(
              LLMObs.LLMMessage.from("assistant", null, Collections.singletonList(toolCall)));
        }
      } else if (item.isMcpCall()) {
        ResponseOutputItem.McpCall mcpCall = item.asMcpCall();
        LLMObs.ToolCall toolCall = ToolCallExtractor.getToolCall(mcpCall);
        List<LLMObs.ToolCall> toolCalls =
            toolCall == null ? null : Collections.singletonList(toolCall);
        String outputText = mcpCall.output().orElse("");
        LLMObs.ToolResult toolResult =
            LLMObs.ToolResult.from(mcpCall.name(), "mcp_tool_result", mcpCall.id(), outputText);
        List<LLMObs.ToolResult> toolResults = Collections.singletonList(toolResult);
        messages.add(LLMObs.LLMMessage.from("assistant", null, toolCalls, toolResults));
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

          String summaryText = null;
          if (!reasoning.summary().isEmpty()) {
            summaryText = reasoning.summary().get(0).text();
          }
          writer.name("summary");
          if (summaryText != null && !summaryText.isEmpty()) {
            writer.value(summaryText);
          } else {
            writer.beginArray().endArray();
          }

          writer.name("encrypted_content");
          if (reasoning.encryptedContent().isPresent()) {
            writer.value(reasoning.encryptedContent().get());
          } else {
            writer.nullValue();
          }

          String id = reasoning.id();
          writer.name("id").value(id == null ? "" : id);

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
