package datadog.trace.instrumentation.openai_java;

import static datadog.trace.instrumentation.openai_java.JsonValueUtils.jsonValueMapToObject;
import static datadog.trace.instrumentation.openai_java.JsonValueUtils.jsonValueToObject;

import com.openai.core.JsonField;
import com.openai.core.JsonValue;
import com.openai.models.Reasoning;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseCustomToolCall;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponsePrompt;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.Tool;
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

    // Keep model_name/output/metadata shape stable on error paths where no response is available.
    if (modelName != null && !modelName.isEmpty()) {
      span.setTag(CommonTags.MODEL_NAME, modelName);
    }
    span.setTag(CommonTags.OUTPUT, Collections.singletonList(LLMObs.LLMMessage.from("", "")));
    span.setTag(CommonTags.METADATA, new HashMap<String, Object>());

    span.setTag(CommonTags.SPAN_KIND, Tags.LLMOBS_LLM_SPAN_KIND);

    List<LLMObs.LLMMessage> inputMessages = new ArrayList<>();

    params
        ._instructions()
        .asString()
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
      Optional<String> inputText = input.text();
      if (inputText.isPresent()) {
        inputMessages.add(LLMObs.LLMMessage.from("user", inputText.get()));
      } else {
        for (ResponseInputItem item : input.response().orElse(Collections.emptyList())) {
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
      Optional<JsonValue> rawValueOpt = params._input().asUnknown();
      if (rawValueOpt.isPresent()) {
        Optional<List<JsonValue>> rawListOpt = rawValueOpt.get().asArray();
        if (rawListOpt.isPresent()) {
          for (JsonValue item : rawListOpt.get()) {
            LLMObs.LLMMessage message = extractMessageFromRawJson(item);
            if (message != null) {
              inputMessages.add(message);
            }
          }
        }
      }
    }

    if (!inputMessages.isEmpty()) {
      span.setTag(CommonTags.INPUT, inputMessages);
    }

    extractReasoningFromParams(params)
        .ifPresent(reasoningMap -> span.setTag(CommonTags.REQUEST_REASONING, reasoningMap));

    extractPromptFromParams(params)
        .ifPresent(prompt -> span.setTag(CommonTags.REQUEST_PROMPT, prompt));

    List<Map<String, Object>> toolDefinitions = extractToolDefinitionsFromParams(params);
    if (!toolDefinitions.isEmpty()) {
      span.setTag(CommonTags.TOOL_DEFINITIONS, toolDefinitions);
    }
  }

  private List<Map<String, Object>> extractToolDefinitionsFromParams(ResponseCreateParams params) {
    Optional<List<Tool>> toolsOpt = params._tools().asKnown();
    if (toolsOpt.isPresent()) {
      List<Map<String, Object>> toolDefinitions = new ArrayList<>();
      for (Tool tool : toolsOpt.get()) {
        if (!tool.isFunction()) {
          continue;
        }
        Map<String, Object> toolDef = extractFunctionToolDefinition(tool.asFunction());
        if (toolDef != null) {
          toolDefinitions.add(toolDef);
        }
      }
      if (!toolDefinitions.isEmpty()) {
        return toolDefinitions;
      }
    }

    Optional<JsonValue> rawToolsOpt = params._tools().asUnknown();
    if (!rawToolsOpt.isPresent()) {
      return Collections.emptyList();
    }
    Optional<List<JsonValue>> rawToolListOpt = rawToolsOpt.get().asArray();
    if (!rawToolListOpt.isPresent()) {
      return Collections.emptyList();
    }

    List<Map<String, Object>> toolDefinitions = new ArrayList<>();
    for (JsonValue rawTool : rawToolListOpt.get()) {
      Map<String, Object> toolDef = extractFunctionToolDefinition(rawTool);
      if (toolDef != null) {
        toolDefinitions.add(toolDef);
      }
    }
    return toolDefinitions;
  }

  private Map<String, Object> extractFunctionToolDefinition(FunctionTool functionTool) {
    String name = functionTool.name();
    if (name == null || name.isEmpty()) {
      return null;
    }

    Map<String, Object> toolDef = new HashMap<>();
    toolDef.put("name", name);
    functionTool.description().ifPresent(desc -> toolDef.put("description", desc));
    functionTool
        .parameters()
        .ifPresent(
            parameters ->
                toolDef.put("schema", jsonValueMapToObject(parameters._additionalProperties())));
    return toolDef;
  }

  private Map<String, Object> extractFunctionToolDefinition(JsonValue rawTool) {
    Optional<Map<String, JsonValue>> toolObjOpt = rawTool.asObject();
    if (!toolObjOpt.isPresent()) {
      return null;
    }

    Map<String, JsonValue> toolObj = toolObjOpt.get();
    String type = getJsonString(toolObj.get("type"));
    if (!"function".equals(type)) {
      return null;
    }

    JsonValue functionObjValue = toolObj.get("function");
    Map<String, JsonValue> functionObj = null;
    if (functionObjValue != null) {
      Optional<Map<String, JsonValue>> nestedFunctionOpt = functionObjValue.asObject();
      if (nestedFunctionOpt.isPresent()) {
        functionObj = nestedFunctionOpt.get();
      }
    }

    String name =
        functionObj == null
            ? getJsonString(toolObj.get("name"))
            : getJsonString(functionObj.get("name"));
    if (name == null || name.isEmpty()) {
      return null;
    }

    Map<String, Object> toolDef = new HashMap<>();
    toolDef.put("name", name);

    String description =
        functionObj == null
            ? getJsonString(toolObj.get("description"))
            : getJsonString(functionObj.get("description"));
    if (description != null) {
      toolDef.put("description", description);
    }

    JsonValue parameters =
        functionObj == null ? toolObj.get("parameters") : functionObj.get("parameters");
    if (parameters != null) {
      Object schema = jsonValueToObject(parameters);
      if (schema != null) {
        toolDef.put("schema", schema);
      }
    }

    return toolDef;
  }

  private LLMObs.LLMMessage extractInputItemMessage(ResponseInputItem item) {
    if (item.isEasyInputMessage()) {
      EasyInputMessage message = item.asEasyInputMessage();
      Optional<String> role = message._role().asKnown().map(EasyInputMessage.Role::asString);
      if (!role.isPresent()) {
        return null;
      }
      String content = extractEasyInputMessageContent(message);
      if (content == null || content.isEmpty()) {
        return null;
      }
      return LLMObs.LLMMessage.from(role.get(), content);
    } else if (item.isMessage()) {
      ResponseInputItem.Message message = item.asMessage();
      Optional<String> role =
          message._role().asKnown().map(ResponseInputItem.Message.Role::asString);
      if (!role.isPresent()) {
        return null;
      }
      String content = extractInputMessageContent(message);
      if (content == null || content.isEmpty()) {
        return null;
      }
      return LLMObs.LLMMessage.from(role.get(), content);
    }

    Optional<ResponseFunctionToolCall> functionCallOpt = item.functionCall();
    if (functionCallOpt.isPresent()) {
      // Function call is mapped to assistant message with tool_calls
      ResponseFunctionToolCall functionCall = functionCallOpt.get();
      LLMObs.ToolCall toolCall = ToolCallExtractor.getToolCall(functionCall);
      if (toolCall != null) {
        List<LLMObs.ToolCall> toolCalls = Collections.singletonList(toolCall);
        return LLMObs.LLMMessage.from("assistant", null, toolCalls);
      }
    }

    Optional<ResponseInputItem.FunctionCallOutput> functionCallOutput = item.functionCallOutput();
    if (functionCallOutput.isPresent()) {
      ResponseInputItem.FunctionCallOutput output = functionCallOutput.get();
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
    Optional<EasyInputMessage.Content> contentValue = message._content().asKnown();
    if (!contentValue.isPresent()) {
      return null;
    }

    EasyInputMessage.Content contentValueTyped = contentValue.get();
    Optional<String> textInput = contentValueTyped.textInput();
    if (textInput.isPresent()) {
      String content = textInput.get();
      return content == null || content.isEmpty() ? null : content;
    }

    StringBuilder contentBuilder = new StringBuilder();
    for (ResponseInputContent content :
        contentValueTyped.responseInputMessageContentList().orElse(Collections.emptyList())) {
      String contentPart = extractInputContentText(content);
      if (contentPart != null) {
        contentBuilder.append(contentPart);
      }
    }
    String result = contentBuilder.toString();
    return result.isEmpty() ? null : result;
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
            Map<String, Object> arguments = ToolCallExtractor.parseArguments(argumentsStr);
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
    Optional<String> inputText = content.inputText().map(v -> v.text());
    if (inputText.isPresent()) {
      return inputText.get();
    }

    Optional<String> inputImage =
        content
            .inputImage()
            .map(v -> v.imageUrl().orElse(v.fileId().orElse(IMAGE_FALLBACK_MARKER)));
    if (inputImage.isPresent()) {
      return inputImage.get();
    }

    return content
        .inputFile()
        .map(v -> v.fileUrl().orElse(v.fileId().orElse(v.filename().orElse(FILE_FALLBACK_MARKER))))
        .orElse(null);
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

    List<LLMObs.LLMMessage> outputMessages =
        extractResponseOutputMessages(response._output().asKnown().orElse(Collections.emptyList()));
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

    response
        ._toolChoice()
        .asKnown()
        .ifPresent(
            toolChoice -> {
              toolChoice
                  .options()
                  .flatMap(v -> v._value().asString())
                  .ifPresent(v -> metadata.put("tool_choice", v));
              if (!metadata.containsKey("tool_choice")) {
                toolChoice
                    .types()
                    .map(v -> v.type().toString().toLowerCase())
                    .ifPresent(v -> metadata.put("tool_choice", v));
              }
              if (!metadata.containsKey("tool_choice") && toolChoice.function().isPresent()) {
                metadata.put("tool_choice", "function");
              }
            });

    response
        ._truncation()
        .asKnown()
        .flatMap(t -> t._value().asString())
        .ifPresent(v -> metadata.put("truncation", v));

    Map<String, Object> textMap = new HashMap<>();
    response
        ._text()
        .asKnown()
        .ifPresent(
            textConfig -> {
              textConfig
                  ._format()
                  .asKnown()
                  .ifPresent(
                      format -> {
                        Map<String, String> formatMap = new HashMap<>();
                        if (format.text().isPresent()) {
                          formatMap.put("type", "text");
                        } else if (format.jsonSchema().isPresent()) {
                          formatMap.put("type", "json_schema");
                        } else if (format.jsonObject().isPresent()) {
                          formatMap.put("type", "json_object");
                        }
                        textMap.put("format", formatMap);
                      });
              textConfig
                  ._verbosity()
                  .asKnown()
                  .flatMap(verbosity -> verbosity._value().asString())
                  .ifPresent(verbosity -> textMap.put("verbosity", verbosity));
            });
    if (!textMap.isEmpty()) {
      metadata.put("text", textMap);
    }

    metadata.put("stream", stream);

    span.setTag(CommonTags.METADATA, metadata);

    response
        ._usage()
        .asKnown()
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

    response
        ._instructions()
        .asKnown()
        .ifPresent(
            instructions -> {
              for (ResponseInputItem item :
                  instructions.inputItemList().orElse(Collections.emptyList())) {
                LLMObs.LLMMessage message = extractInputItemMessage(item);
                if (message != null) {
                  messages.add(message);
                }
              }
            });
    return messages;
  }

  private Optional<Map<String, Object>> extractPromptFromParams(ResponseCreateParams params) {
    Optional<ResponsePrompt> typedPromptOpt = params._prompt().asKnown();
    if (typedPromptOpt.isPresent()) {
      Optional<Map<String, Object>> extractedPrompt = extractPrompt(typedPromptOpt.get());
      if (extractedPrompt.isPresent()) {
        return extractedPrompt;
      }
    }

    Optional<JsonValue> rawPromptOpt = params._prompt().asUnknown();
    if (!rawPromptOpt.isPresent()) {
      return Optional.empty();
    }

    Optional<Map<String, JsonValue>> rawPromptObjOpt = rawPromptOpt.get().asObject();
    if (!rawPromptObjOpt.isPresent()) {
      return Optional.empty();
    }

    return extractPrompt(rawPromptObjOpt.get());
  }

  private Optional<Map<String, Object>> extractPrompt(ResponsePrompt prompt) {
    Map<String, Object> promptMap = new LinkedHashMap<>();

    String id = prompt._id().asString().orElse(null);
    if (id != null && !id.isEmpty()) {
      promptMap.put("id", id);
    }
    prompt._version().asString().ifPresent(version -> promptMap.put("version", version));

    Optional<ResponsePrompt.Variables> typedVariablesOpt = prompt._variables().asKnown();
    if (typedVariablesOpt.isPresent()) {
      Map<String, Object> normalized = normalizePromptVariables(typedVariablesOpt.get());
      if (!normalized.isEmpty()) {
        promptMap.put("variables", normalized);
      }
    }

    return promptMap.isEmpty() ? Optional.empty() : Optional.of(promptMap);
  }

  private Optional<Map<String, Object>> extractPrompt(Map<String, JsonValue> promptObj) {
    Map<String, Object> promptMap = new LinkedHashMap<>();

    String id = getJsonString(promptObj.get("id"));
    if (id != null && !id.isEmpty()) {
      promptMap.put("id", id);
    }

    String version = getJsonString(promptObj.get("version"));
    if (version != null && !version.isEmpty()) {
      promptMap.put("version", version);
    }

    JsonValue variablesValue = promptObj.get("variables");
    if (variablesValue != null) {
      Optional<Map<String, JsonValue>> variablesObjOpt = variablesValue.asObject();
      if (variablesObjOpt.isPresent()) {
        Map<String, Object> normalized = normalizePromptVariables(variablesObjOpt.get());
        if (!normalized.isEmpty()) {
          promptMap.put("variables", normalized);
        }
      }
    }

    return promptMap.isEmpty() ? Optional.empty() : Optional.of(promptMap);
  }

  private Map<String, Object> normalizePromptVariables(ResponsePrompt.Variables variables) {
    return normalizePromptVariables(variables._additionalProperties());
  }

  private Map<String, Object> normalizePromptVariables(Map<String, JsonValue> variables) {
    Map<String, Object> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, JsonValue> entry : variables.entrySet()) {
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
      Optional<ResponseFunctionToolCall> functionCallOpt = item.functionCall();
      if (functionCallOpt.isPresent()) {
        ResponseFunctionToolCall functionCall = functionCallOpt.get();
        LLMObs.ToolCall toolCall = ToolCallExtractor.getToolCall(functionCall);
        if (toolCall != null) {
          List<LLMObs.ToolCall> toolCalls = Collections.singletonList(toolCall);
          messages.add(LLMObs.LLMMessage.from("assistant", null, toolCalls));
        }
        continue;
      }

      Optional<ResponseCustomToolCall> customToolCallOpt = item.customToolCall();
      if (customToolCallOpt.isPresent()) {
        ResponseCustomToolCall customToolCall = customToolCallOpt.get();
        LLMObs.ToolCall toolCall = ToolCallExtractor.getToolCall(customToolCall);
        if (toolCall != null) {
          messages.add(
              LLMObs.LLMMessage.from("assistant", null, Collections.singletonList(toolCall)));
        }
        continue;
      }

      Optional<ResponseOutputItem.McpCall> mcpCallOpt = item.mcpCall();
      if (mcpCallOpt.isPresent()) {
        ResponseOutputItem.McpCall mcpCall = mcpCallOpt.get();
        LLMObs.ToolCall toolCall = ToolCallExtractor.getToolCall(mcpCall);
        List<LLMObs.ToolCall> toolCalls =
            toolCall == null ? null : Collections.singletonList(toolCall);
        String outputText = mcpCall.output().orElse("");
        LLMObs.ToolResult toolResult =
            LLMObs.ToolResult.from(mcpCall.name(), "mcp_tool_result", mcpCall.id(), outputText);
        List<LLMObs.ToolResult> toolResults = Collections.singletonList(toolResult);
        messages.add(LLMObs.LLMMessage.from("assistant", null, toolCalls, toolResults));
        continue;
      }

      Optional<ResponseOutputMessage> messageOpt = item.message();
      if (messageOpt.isPresent()) {
        ResponseOutputMessage message = messageOpt.get();
        String textContent = extractMessageContent(message);
        Optional<String> roleOpt = message._role().asString();
        String role = roleOpt.orElse("assistant");
        messages.add(LLMObs.LLMMessage.from(role, textContent));
        continue;
      }

      Optional<ResponseReasoningItem> reasoningOpt = item.reasoning();
      if (reasoningOpt.isPresent()) {
        ResponseReasoningItem reasoning = reasoningOpt.get();
        try (JsonWriter writer = new JsonWriter()) {
          writer.beginObject();

          String summaryText =
              reasoning
                  ._summary()
                  .asKnown()
                  .filter(summary -> !summary.isEmpty())
                  .flatMap(summary -> summary.get(0)._text().asString())
                  .orElse(null);
          writer.name("summary");
          if (summaryText != null && !summaryText.isEmpty()) {
            writer.value(summaryText);
          } else {
            writer.beginArray().endArray();
          }

          writer.name("encrypted_content");
          Optional<String> encryptedContent = reasoning._encryptedContent().asString();
          if (encryptedContent.isPresent()) {
            writer.value(encryptedContent.get());
          } else {
            writer.nullValue();
          }

          String id = reasoning._id().asString().orElse(null);
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
    for (ResponseOutputMessage.Content content :
        message._content().asKnown().orElse(Collections.emptyList())) {
      content.outputText().ifPresent(outputText -> contentBuilder.append(outputText.text()));
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
