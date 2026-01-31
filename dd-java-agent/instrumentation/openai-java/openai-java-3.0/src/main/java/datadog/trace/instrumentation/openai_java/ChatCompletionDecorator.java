package datadog.trace.instrumentation.openai_java;

import com.openai.helpers.ChatCompletionAccumulator;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
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
    if (!llmObsEnabled) {
      return;
    }

    span.setTag(CommonTags.SPAN_KIND, Tags.LLMOBS_LLM_SPAN_KIND);
    if (params == null) {
      return;
    }
    params
        .model()
        ._value()
        .asString()
        .ifPresent(str -> span.setTag(CommonTags.OPENAI_REQUEST_MODEL, str));

    span.setTag(
        CommonTags.INPUT,
        params.messages().stream()
            .map(ChatCompletionDecorator::llmMessage)
            .collect(Collectors.toList()));

    Map<String, Object> metadata = new HashMap<>();
    // maxTokens is deprecated but integration tests missing to provide maxCompletionTokens
    params.maxTokens().ifPresent(v -> metadata.put("max_tokens", v));
    params.temperature().ifPresent(v -> metadata.put("temperature", v));
    if (stream) {
      metadata.put("stream", true);
    }
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
  }

  private static LLMObs.LLMMessage llmMessage(ChatCompletionMessageParam m) {
    String role = "unknown";
    String content = null;
    if (m.isAssistant()) {
      role = "assistant";
      content = m.asAssistant().content().map(v -> v.text().orElse(null)).orElse(null);
    } else if (m.isDeveloper()) {
      role = "developer";
      content = m.asDeveloper().content().text().orElse(null);
    } else if (m.isSystem()) {
      role = "system";
      content = m.asSystem().content().text().orElse(null);
    } else if (m.isTool()) {
      role = "tool";
      content = m.asTool().content().text().orElse(null);
    } else if (m.isUser()) {
      role = "user";
      content = m.asUser().content().text().orElse(null);
    }
    return LLMObs.LLMMessage.from(role, content);
  }

  public void withChatCompletion(AgentSpan span, ChatCompletion completion) {
    if (!llmObsEnabled) {
      return;
    }
    String modelName = completion.model();
    span.setTag(CommonTags.OPENAI_RESPONSE_MODEL, modelName);
    span.setTag(CommonTags.MODEL_NAME, modelName);

    List<LLMObs.LLMMessage> output =
        completion.choices().stream()
            .map(ChatCompletionDecorator::llmMessage)
            .collect(Collectors.toList());
    span.setTag(CommonTags.OUTPUT, output);

    completion
        .usage()
        .ifPresent(
            usage -> {
              span.setTag(CommonTags.INPUT_TOKENS, usage.promptTokens());
              span.setTag(CommonTags.OUTPUT_TOKENS, usage.completionTokens());
              span.setTag(CommonTags.TOTAL_TOKENS, usage.totalTokens());
            });
  }

  private static LLMObs.LLMMessage llmMessage(ChatCompletion.Choice choice) {
    ChatCompletionMessage msg = choice.message();
    Optional<?> roleOpt = msg._role().asString();
    String role = "unknown";
    if (roleOpt.isPresent()) {
      role = String.valueOf(roleOpt.get());
    }
    String content = msg.content().orElse(null);

    Optional<List<ChatCompletionMessageToolCall>> toolCallsOpt = msg.toolCalls();
    if (toolCallsOpt.isPresent() && !toolCallsOpt.get().isEmpty()) {
      List<LLMObs.ToolCall> toolCalls = new ArrayList<>();
      for (ChatCompletionMessageToolCall toolCall : toolCallsOpt.get()) {
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
