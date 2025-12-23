package datadog.trace.instrumentation.openai_java;

import static datadog.trace.instrumentation.openai_java.OpenAiDecorator.REQUEST_MODEL;
import static datadog.trace.instrumentation.openai_java.OpenAiDecorator.RESPONSE_MODEL;

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
    span.setTag("openai.request.endpoint", "v1/completions");
    span.setTag("openai.request.method", "POST");
    if (!llmObsEnabled) {
      return;
    }

    span.setTag("_ml_obs_tag.span.kind", Tags.LLMOBS_LLM_SPAN_KIND);
    if (params == null) {
      return;
    }

    params.model()._value().asString().ifPresent(str -> span.setTag(REQUEST_MODEL, str));
    params
        .prompt()
        .flatMap(p -> p.string())
        .ifPresent(
            input ->
                span.setTag(
                    "_ml_obs_tag.input",
                    Collections.singletonList(LLMObs.LLMMessage.from(null, input))));

    Map<String, Object> metadata = new HashMap<>();
    params.maxTokens().ifPresent(v -> metadata.put("max_tokens", v));
    params.temperature().ifPresent(v -> metadata.put("temperature", v));
    span.setTag("_ml_obs_tag.metadata", metadata);
  }

  public void withCompletion(AgentSpan span, Completion completion) {
    if (!llmObsEnabled) {
      return;
    }

    String modelName = completion.model();
    span.setTag(RESPONSE_MODEL, modelName);
    span.setTag("_ml_obs_tag.model_name", modelName);
    span.setTag("_ml_obs_tag.model_provider", "openai");

    List<LLMObs.LLMMessage> output =
        completion.choices().stream()
            .map(v -> LLMObs.LLMMessage.from(null, v.text()))
            .collect(Collectors.toList());
    span.setTag("_ml_obs_tag.output", output);

    completion
        .usage()
        .ifPresent(
            usage -> {
              span.setTag("_ml_obs_metric.input_tokens", usage.promptTokens());
              span.setTag("_ml_obs_metric.output_tokens", usage.completionTokens());
              span.setTag("_ml_obs_metric.total_tokens", usage.totalTokens());
            });
  }

  public void withCompletions(AgentSpan span, List<Completion> completions) {
    if (!llmObsEnabled) {
      return;
    }

    if (!completions.isEmpty()) {
      withCompletion(span, completions.get(0));
    }
  }
}
