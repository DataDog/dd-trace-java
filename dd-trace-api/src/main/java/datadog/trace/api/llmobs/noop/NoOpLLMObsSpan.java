package datadog.trace.api.llmobs.noop;

import datadog.trace.api.llmobs.LLMObsSpan;

public class NoOpLLMObsSpan implements LLMObsSpan {
  public static final LLMObsSpan INSTANCE = new NoOpLLMObsSpan();

  @Override
  public void annotate(String key, Object value) {}
}
