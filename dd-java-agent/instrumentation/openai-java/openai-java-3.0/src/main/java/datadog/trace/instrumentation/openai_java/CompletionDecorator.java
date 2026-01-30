package datadog.trace.instrumentation.openai_java;

import com.openai.models.completions.Completion;
import com.openai.models.completions.CompletionCreateParams;
import datadog.trace.api.Config;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CompletionDecorator {
  public static final CompletionDecorator DECORATE = new CompletionDecorator();

  private static final CharSequence COMPLETIONS_CREATE = UTF8BytesString.create("createCompletion");

  private final boolean llmObsEnabled = Config.get().isLlmObsEnabled();

  public void withCompletionCreateParams(AgentSpan span, CompletionCreateParams params) {
    span.setResourceName(COMPLETIONS_CREATE);
    span.setTag(CommonTags.OPENAI_REQUEST_ENDPOINT, "/v1/completions");
    span.setTag(CommonTags.OPENAI_REQUEST_METHOD, "POST");
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
    params
        .prompt()
        .flatMap(p -> p.string())
        .ifPresent(
            input ->
                span.setTag(
                    CommonTags.INPUT,
                    Collections.singletonList(LLMObs.LLMMessage.from(null, input))));

    Map<String, Object> metadata = new HashMap<>();
    params.maxTokens().ifPresent(v -> metadata.put("max_tokens", v));
    params.temperature().ifPresent(v -> metadata.put("temperature", v));
    params
        .streamOptions()
        .ifPresent(
            v -> {
              if (v.includeUsage().orElse(false)) {
                metadata.put("stream_options", Collections.singletonMap("include_usage", true));
              }
            });
    span.setTag(CommonTags.METADATA, metadata);
  }

  public void withCompletion(AgentSpan span, Completion completion) {
    if (!llmObsEnabled) {
      return;
    }

    String modelName = completion.model();
    span.setTag(CommonTags.OPENAI_RESPONSE_MODEL, modelName);
    span.setTag(CommonTags.MODEL_NAME, modelName);
    span.setTag(CommonTags.MODEL_PROVIDER, "openai");

    List<LLMObs.LLMMessage> output =
        completion.choices().stream()
            .map(v -> LLMObs.LLMMessage.from(null, v.text()))
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

  public void withCompletions(AgentSpan span, List<Completion> completions) {
    if (!llmObsEnabled) {
      return;
    }

    if (completions.isEmpty()) {
      return;
    }

    Completion firstCompletion = completions.get(0);
    String modelName = firstCompletion.model();
    span.setTag(CommonTags.OPENAI_RESPONSE_MODEL, modelName);
    span.setTag(CommonTags.MODEL_NAME, modelName);
    span.setTag(CommonTags.MODEL_PROVIDER, "openai");

    Map<Long, StringBuilder> textByChoiceIndex = new HashMap<>();
    for (Completion completion : completions) {
      completion
          .choices()
          .forEach(
              choice -> {
                long index = choice.index();
                textByChoiceIndex
                    .computeIfAbsent(index, k -> new StringBuilder())
                    .append(choice.text());
              });
    }

    List<LLMObs.LLMMessage> output =
        textByChoiceIndex.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> LLMObs.LLMMessage.from(null, entry.getValue().toString()))
            .collect(Collectors.toList());
    span.setTag(CommonTags.OUTPUT, output);

    Completion lastCompletion = completions.get(completions.size() - 1);
    lastCompletion
        .usage()
        .ifPresent(
            usage -> {
              span.setTag(CommonTags.INPUT_TOKENS, usage.promptTokens());
              span.setTag(CommonTags.OUTPUT_TOKENS, usage.completionTokens());
              span.setTag(CommonTags.TOTAL_TOKENS, usage.totalTokens());
            });
  }
}
