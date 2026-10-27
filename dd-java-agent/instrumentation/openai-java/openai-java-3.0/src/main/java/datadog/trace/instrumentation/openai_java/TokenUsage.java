package datadog.trace.instrumentation.openai_java;

import datadog.trace.api.Config;
import datadog.trace.api.llmobs.GenAiApmTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

/**
 * Routes a token count to the LLM Observability metric when LLM Observability is enabled, and to
 * the matching {@code gen_ai.usage.*} APM metric when it is not.
 */
final class TokenUsage {
  private static final boolean LLM_OBS_ENABLED = Config.get().isLlmObsEnabled();

  static void set(AgentSpan span, String llmObsMetric, Number value) {
    if (span == null || value == null) {
      return;
    }
    if (LLM_OBS_ENABLED) {
      span.setTag(llmObsMetric, value);
    } else {
      GenAiApmTags.usage(span, llmObsMetric, value);
    }
  }

  private TokenUsage() {}
}
