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
import java.util.Optional;
import java.util.stream.Collectors;

public class CompletionDecorator {
  public static final CompletionDecorator DECORATE = new CompletionDecorator();

  private static final CharSequence COMPLETIONS_CREATE = UTF8BytesString.create("createCompletion");

  private final boolean llmObsEnabled = Config.get().isLlmObsEnabled();

  public void withCompletionCreateParams(AgentSpan span, CompletionCreateParams params) {
    span.setResourceName(COMPLETIONS_CREATE);
    span.setTag(CommonTags.OPENAI_REQUEST_ENDPOINT, "/v1/completions");
    if (params == null) {
      return;
    }
    Optional<String> modelName = extractCompletionModelName(params);
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
    extractPromptText(params)
        .ifPresent(
            input ->
                span.setTag(
                    CommonTags.INPUT,
                    Collections.singletonList(LLMObs.LLMMessage.from("", input))));

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
    String modelName = completion._model().asString().orElse(null);
    span.setTag(CommonTags.OPENAI_RESPONSE_MODEL, modelName);
    span.setTag(CommonTags.MODEL_NAME, modelName);

    if (!llmObsEnabled) {
      return;
    }

    List<LLMObs.LLMMessage> output =
        completion._choices().asKnown().orElse(Collections.emptyList()).stream()
            .map(v -> LLMObs.LLMMessage.from("", v._text().asString().orElse(null)))
            .collect(Collectors.toList());
    span.setTag(CommonTags.OUTPUT, output);

    completion
        ._usage()
        .asKnown()
        .ifPresent(
            usage -> {
              usage
                  ._promptTokens()
                  .asKnown()
                  .ifPresent(v -> span.setTag(CommonTags.INPUT_TOKENS, v));
              usage
                  ._completionTokens()
                  .asKnown()
                  .ifPresent(v -> span.setTag(CommonTags.OUTPUT_TOKENS, v));
              usage
                  ._totalTokens()
                  .asKnown()
                  .ifPresent(v -> span.setTag(CommonTags.TOTAL_TOKENS, v));
            });
  }

  public void withCompletions(AgentSpan span, List<Completion> completions) {
    if (completions.isEmpty()) {
      return;
    }

    Completion firstCompletion = completions.get(0);
    String modelName = firstCompletion._model().asString().orElse(null);
    span.setTag(CommonTags.OPENAI_RESPONSE_MODEL, modelName);
    span.setTag(CommonTags.MODEL_NAME, modelName);

    if (!llmObsEnabled) {
      return;
    }

    Map<Long, StringBuilder> textByChoiceIndex = new HashMap<>();
    for (Completion completion : completions) {
      completion
          ._choices()
          .asKnown()
          .orElse(Collections.emptyList())
          .forEach(
              choice -> {
                long index = choice._index().asKnown().orElse(0L);
                textByChoiceIndex
                    .computeIfAbsent(index, k -> new StringBuilder())
                    .append(choice._text().asString().orElse(""));
              });
    }

    List<LLMObs.LLMMessage> output =
        textByChoiceIndex.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> LLMObs.LLMMessage.from("", entry.getValue().toString()))
            .collect(Collectors.toList());
    span.setTag(CommonTags.OUTPUT, output);

    Completion lastCompletion = completions.get(completions.size() - 1);
    lastCompletion
        ._usage()
        .asKnown()
        .ifPresent(
            usage -> {
              usage
                  ._promptTokens()
                  .asKnown()
                  .ifPresent(v -> span.setTag(CommonTags.INPUT_TOKENS, v));
              usage
                  ._completionTokens()
                  .asKnown()
                  .ifPresent(v -> span.setTag(CommonTags.OUTPUT_TOKENS, v));
              usage
                  ._totalTokens()
                  .asKnown()
                  .ifPresent(v -> span.setTag(CommonTags.TOTAL_TOKENS, v));
            });
  }

  private Optional<String> extractCompletionModelName(CompletionCreateParams params) {
    Optional<String> modelName =
        params._model().asKnown().flatMap(model -> model._value().asString());
    return modelName.isPresent() ? modelName : params._model().asString();
  }

  private Optional<String> extractPromptText(CompletionCreateParams params) {
    Optional<String> promptText =
        params._prompt().asKnown().flatMap(CompletionCreateParams.Prompt::string);
    if (promptText.isPresent()) {
      return promptText;
    }
    return params._prompt().asUnknown().flatMap(v -> v.asString());
  }
}
