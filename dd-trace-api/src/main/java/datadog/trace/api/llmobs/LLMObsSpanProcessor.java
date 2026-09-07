package datadog.trace.api.llmobs;

import javax.annotation.Nullable;

/** Processes LLM Observability spans before they are sent. */
@FunctionalInterface
public interface LLMObsSpanProcessor {

  /**
   * Processes an LLM Observability span.
   *
   * <p>The processor may mutate and return {@code span}, or return {@code null} to omit the span
   * from LLM Observability.
   *
   * @param span the span being processed
   * @return the span to send, or {@code null} to omit it
   */
  @Nullable
  LLMObsSpanData process(LLMObsSpanData span);
}
