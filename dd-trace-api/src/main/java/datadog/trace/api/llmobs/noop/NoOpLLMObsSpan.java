package datadog.trace.api.llmobs.noop;

import datadog.trace.api.llmobs.LLMObsSpan;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

public class NoOpLLMObsSpan implements LLMObsSpan {
  public static final LLMObsSpan INSTANCE = new NoOpLLMObsSpan();

  @Override
  public void annotate(
      @Nullable List<Map<String, Object>> inputData,
      @Nullable List<Map<String, Object>> outputData,
      @Nullable Map<String, Object> metadata,
      @Nullable Map<String, Number> metrics,
      @Nullable Map<String, Object> tags) {}

  @Override
  public void annotate(
      @Nullable String inputData,
      @Nullable String outputData,
      @Nullable Map<String, Object> metadata,
      @Nullable Map<String, Number> metrics,
      @Nullable Map<String, Object> tags) {}
}
