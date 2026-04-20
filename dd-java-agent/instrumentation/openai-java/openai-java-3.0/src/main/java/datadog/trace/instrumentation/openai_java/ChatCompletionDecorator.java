package datadog.trace.instrumentation.openai_java;

import static datadog.trace.instrumentation.openai_java.JsonValueUtils.jsonValueMapToObject;
import static datadog.trace.instrumentation.openai_java.JsonValueUtils.jsonValueToObject;

import com.openai.core.JsonValue;
import com.openai.helpers.ChatCompletionAccumulator;
import com.openai.models.FunctionDefinition;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionTool;
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
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class ChatCompletionDecorator {
  public static final ChatCompletionDecorator DECORATE = new ChatCompletionDecorator();
  private static final CharSequence CHAT_COMPLETIONS_CREATE =
      UTF8BytesString.create("createChatCompletion");

  private final boolean llmObsEnabled = Config.get().isLlmObsEnabled();

  public void withChatCompletionCreateParams(
      AgentSpan span, ChatCompletionCreateParams params, boolean stream) {
    span.setResourceName(CHAT_COMPLETIONS_CREATE);
    span.setTag(CommonTags.OPENAI_REQUEST_ENDPOINT, "/v1/chat/completions");
    if (params == null) {
      return;
    }
    Optional<String> modelName = extractChatModelName(params);
    modelName.ifPresent(str -> span.setTag(CommonTags.OPENAI_REQUEST_MODEL, str));

    if (!llmObsEnabled) {
      return;
    }

    // Keep model_name and output shape stable on error paths where no response is available.
    modelName.ifPresent(
        str -> {
          span.setTag(CommonTags.MODEL_NAME, str);
          span.setTag(CommonTags.OUTPUT, Collections.singletonList(LLMObs.LLMMessage.from("", "")));
        });

    span.setTag(CommonTags.SPAN_KIND, Tags.LLMOBS_LLM_SPAN_KIND);

    span.setTag(
        CommonTags.INPUT,
        params.messages().stream()
            .map(ChatCompletionDecorator::llmMessage)
            .filter(Objects::nonNull)
            .collect(Collectors.toList()));

    Map<String, Object> metadata = new HashMap<>();
    // maxTokens is deprecated but integration tests missing to provide maxCompletionTokens
    params.maxTokens().ifPresent(v -> metadata.put("max_tokens", v));
    params.temperature().ifPresent(v -> metadata.put("temperature", v));
    metadata.put("stream", stream);
    params
        .streamOptions()
        .ifPresent(
            v -> {
              if (v.includeUsage().orElse(false)) {
                metadata.put("stream_options", Collections.singletonMap("include_usage", true));
              }
            });
    params.topP().ifPresent(v -> metadata.put("top_p", v));
    params.frequencyPenalty().ifPresent(v -> metadata.put("frequency_penalty", v));
    params.presencePenalty().ifPresent(v -> metadata.put("presence_penalty", v));
    params.n().ifPresent(v -> metadata.put("n", v));
    params.seed().ifPresent(v -> metadata.put("seed", v));
    span.setTag(CommonTags.METADATA, metadata);
    params
        .toolChoice()
        .ifPresent(
            toolChoice -> {
              String choice = null;
              if (toolChoice.isAuto()) {
                choice = "auto";
              } else if (toolChoice.isAllowedToolChoice()) {
                choice = "allowed_tools";
              } else if (toolChoice.isNamedToolChoice()) {
                choice = "function";
              } else if (toolChoice.isNamedToolChoiceCustom()) {
                choice = "custom";
              }
              if (choice != null) {
                metadata.put("tool_choice", choice);
              }
            });

    List<ChatCompletionTool> tools = params._tools().asKnown().orElse(Collections.emptyList());
    if (!tools.isEmpty()) {
      span.setTag(CommonTags.TOOL_DEFINITIONS, extractToolDefinitions(tools));
    }
  }

  private Optional<String> extractChatModelName(ChatCompletionCreateParams params) {
    Optional<String> modelName =
        params._model().asKnown().flatMap(model -> model._value().asString());
    return modelName.isPresent() ? modelName : params._model().asString();
  }

  private List<Map<String, Object>> extractToolDefinitions(List<ChatCompletionTool> tools) {
    List<Map<String, Object>> toolDefinitions = new ArrayList<>();
    for (ChatCompletionTool tool : tools) {
      if (tool.isFunction()) {
        Map<String, Object> toolDef = extractFunctionToolDef(tool.asFunction());
        if (toolDef != null) {
          toolDefinitions.add(toolDef);
        }
      }
    }
    return toolDefinitions;
  }

  private static Map<String, Object> extractFunctionToolDef(ChatCompletionFunctionTool funcTool) {
    // Try typed access first (works when built programmatically)
    Optional<FunctionDefinition> funcDefOpt = funcTool._function().asKnown();
    if (funcDefOpt.isPresent()) {
      FunctionDefinition funcDef = funcDefOpt.get();
      Map<String, Object> toolDef = new HashMap<>();
      toolDef.put("name", funcDef.name());
      funcDef.description().ifPresent(desc -> toolDef.put("description", desc));
      funcDef
          .parameters()
          .ifPresent(
              params ->
                  toolDef.put("schema", jsonValueMapToObject(params._additionalProperties())));
      return toolDef;
    }

    // Fall back to raw JSON extraction (when deserialized from HTTP request)
    Optional<JsonValue> rawOpt = funcTool._function().asUnknown();
    if (!rawOpt.isPresent()) {
      return null;
    }
    Optional<Map<String, JsonValue>> objOpt = rawOpt.get().asObject();
    if (!objOpt.isPresent()) {
      return null;
    }
    Map<String, JsonValue> obj = objOpt.get();
    JsonValue nameValue = obj.get("name");
    if (nameValue == null) {
      return null;
    }
    Optional<String> nameOpt = nameValue.asString();
    if (!nameOpt.isPresent()) {
      return null;
    }
    Map<String, Object> toolDef = new HashMap<>();
    toolDef.put("name", nameOpt.get());
    JsonValue descValue = obj.get("description");
    if (descValue != null) {
      descValue.asString().ifPresent(desc -> toolDef.put("description", desc));
    }
    JsonValue paramsValue = obj.get("parameters");
    if (paramsValue != null) {
      Object schema = jsonValueToObject(paramsValue);
      if (schema != null) {
        toolDef.put("schema", schema);
      }
    }
    return toolDef;
  }

  private static LLMObs.LLMMessage llmMessage(ChatCompletionMessageParam m) {
    if (m.isAssistant()) {
      return LLMObs.LLMMessage.from(
          "assistant", m.asAssistant().content().map(v -> v.text().orElse(null)).orElse(null));
    } else if (m.isDeveloper()) {
      return LLMObs.LLMMessage.from("developer", m.asDeveloper().content().text().orElse(null));
    } else if (m.isSystem()) {
      return LLMObs.LLMMessage.from("system", m.asSystem().content().text().orElse(null));
    } else if (m.isTool()) {
      return LLMObs.LLMMessage.from("tool", m.asTool().content().text().orElse(null));
    } else if (m.isUser()) {
      return LLMObs.LLMMessage.from("user", m.asUser().content().text().orElse(null));
    }
    return null;
  }

  public void withChatCompletion(AgentSpan span, ChatCompletion completion) {
    String modelName = completion._model().asString().orElse(null);
    span.setTag(CommonTags.OPENAI_RESPONSE_MODEL, modelName);
    span.setTag(CommonTags.MODEL_NAME, modelName);

    if (!llmObsEnabled) {
      return;
    }

    List<LLMObs.LLMMessage> output =
        completion._choices().asKnown().orElse(Collections.emptyList()).stream()
            .map(ChatCompletionDecorator::llmMessage)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    span.setTag(CommonTags.OUTPUT, output);

    completion
        ._usage()
        .asKnown()
        .ifPresent(
            usage -> {
              span.setTag(CommonTags.INPUT_TOKENS, usage.promptTokens());
              span.setTag(CommonTags.OUTPUT_TOKENS, usage.completionTokens());
              span.setTag(CommonTags.TOTAL_TOKENS, usage.totalTokens());
              usage
                  .promptTokensDetails()
                  .flatMap(details -> details.cachedTokens())
                  .ifPresent(v -> span.setTag(CommonTags.CACHE_READ_INPUT_TOKENS, v));
            });
  }

  private static LLMObs.LLMMessage llmMessage(ChatCompletion.Choice choice) {
    Optional<ChatCompletionMessage> msgOpt = choice._message().asKnown();
    if (!msgOpt.isPresent()) {
      return null;
    }

    ChatCompletionMessage msg = msgOpt.get();
    Optional<String> roleOpt = msg._role().asString();
    if (!roleOpt.isPresent()) {
      return null;
    }
    String role = roleOpt.get();
    String content = msg._content().asString().orElse("");

    List<ChatCompletionMessageToolCall> toolCallsOpt =
        msg._toolCalls().asKnown().orElse(Collections.emptyList());
    if (!toolCallsOpt.isEmpty()) {
      List<LLMObs.ToolCall> toolCalls = new ArrayList<>();
      for (ChatCompletionMessageToolCall toolCall : toolCallsOpt) {
        LLMObs.ToolCall llmObsToolCall = ToolCallExtractor.getToolCall(toolCall);
        if (llmObsToolCall != null) {
          toolCalls.add(llmObsToolCall);
        }
      }

      if (!toolCalls.isEmpty()) {
        return LLMObs.LLMMessage.from(role, content, toolCalls);
      }
    }

    return LLMObs.LLMMessage.from(role, content);
  }

  public void withChatCompletionChunks(AgentSpan span, List<ChatCompletionChunk> chunks) {
    if (!llmObsEnabled) {
      return;
    }
    ChatCompletionAccumulator accumulator = ChatCompletionAccumulator.create();
    for (ChatCompletionChunk chunk : chunks) {
      accumulator.accumulate(chunk);
    }
    ChatCompletion chatCompletion = accumulator.chatCompletion();
    withChatCompletion(span, chatCompletion);
  }
}
