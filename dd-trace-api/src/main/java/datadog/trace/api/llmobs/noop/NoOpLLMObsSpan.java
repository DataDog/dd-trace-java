package datadog.trace.api.llmobs.noop;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsSpan;
import java.util.List;
import java.util.Map;

public class NoOpLLMObsSpan implements LLMObsSpan {
  public static final LLMObsSpan INSTANCE = new NoOpLLMObsSpan();

  @Override
  public void annotateIO(List<LLMObs.LLMMessage> inputData, List<LLMObs.LLMMessage> outputData) {}

  @Override
  public void annotateIO(String inputData, String outputData) {}

  @Override
  public void setMetadata(Map<String, Object> metadata) {}

  @Override
  public void setMetrics(Map<String, Number> metrics) {}

  @Override
  public void setMetric(CharSequence key, int value) {}

  @Override
  public void setMetric(CharSequence key, long value) {}

  @Override
  public void setMetric(CharSequence key, double value) {}

  @Override
  public void setTags(Map<String, Object> tags) {}

  @Override
  public void setTag(String key, String value) {}

  @Override
  public void setTag(String key, boolean value) {}

  @Override
  public void setTag(String key, int value) {}

  @Override
  public void setTag(String key, long value) {}

  @Override
  public void setTag(String key, double value) {}

  @Override
  public void setError(boolean error) {}

  @Override
  public void setErrorMessage(String errorMessage) {}

  @Override
  public void addThrowable(Throwable throwable) {}

  @Override
  public void finish() {}

  @Override
  public DDTraceId getTraceId() {
    return DDTraceId.ZERO;
  }

  @Override
  public long getSpanId() {
    return 0;
  }
}
