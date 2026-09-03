package datadog.trace.api.llmobs.noop;

import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsSpan;
import java.io.Closeable;
import java.util.Map;

public class NoOpLLMObsPropagator implements LLMObs.LLMObsPropagator {
  public static final NoOpLLMObsPropagator INSTANCE = new NoOpLLMObsPropagator();

  @Override
  public Map<String, String> injectDistributedHeaders(
      LLMObsSpan span, Map<String, String> headers) {
    return headers;
  }

  @Override
  public Closeable activateDistributedHeaders(Map<String, String> headers) {
    return () -> {};
  }
}
