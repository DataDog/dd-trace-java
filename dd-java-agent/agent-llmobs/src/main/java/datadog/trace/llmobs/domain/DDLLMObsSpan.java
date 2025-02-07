package datadog.trace.llmobs.domain;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.llmobs.LLMObsConstants;
import datadog.trace.api.llmobs.LLMObsSpan;
import datadog.trace.api.llmobs.LLMObsTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DDLLMObsSpan implements LLMObsSpan {

  private final AgentSpan span;

  private boolean finished = false;

  private static final Logger LOGGER = LoggerFactory.getLogger(DDLLMObsSpan.class);

  public DDLLMObsSpan(
      @Nonnull String kind,
      String spanName,
      @Nonnull String mlApp,
      String sessionID,
      @Nonnull String serviceName) {

    if (null == spanName || spanName.isEmpty()) {
      spanName = kind;
    }

    AgentTracer.SpanBuilder spanBuilder =
        AgentTracer.get()
            // set operation name as span kind so that apm span will have span kind as the operation
            // name, we will remap this later at write time
            .buildSpan(LLMObsConstants.LLM_OBS_INSTRUMENTATION_NAME, spanName)
            .withServiceName(serviceName)
            .withSpanType(DDSpanTypes.LLMOBS);

    this.span = spanBuilder.start();
    this.span.setTag(Tags.SPAN_KIND, kind);
    this.span.setTag(LLMObsTags.LLMOBS_TAG_PREFIX + LLMObsTags.ML_APP, mlApp);
    if (sessionID != null && !sessionID.isEmpty()) {
      this.span.setTag(LLMObsTags.LLMOBS_TAG_PREFIX + LLMObsTags.SESSION_ID, sessionID);
    }
  }

  public AgentSpanContext getContext() {
    return this.span.context();
  }

  @Override
  public String toString() {
    return super.toString()
        + ", trace_id="
        + this.span.context().getTraceId()
        + ", span_id="
        + this.span.context().getSpanId()
        + ", ml_app="
        + this.span.getTag(LLMObsTags.ML_APP)
        + ", service="
        + this.span.getServiceName()
        + ", span_kind="
        + this.span.getTag(Tags.SPAN_KIND)
        + ", context="
        + this.span.context();
  }

  @Override
  public void annotateIO(
      List<Map<String, Object>> inputData, List<Map<String, Object>> outputData) {
    if (finished) {
      return;
    }
    LOGGER.warn("ANNOTATE IN {} OUT {}", inputData, outputData);
    if (inputData != null && !inputData.isEmpty()) {
      this.span.setTag(LLMObsTags.LLMOBS_TAG_PREFIX + LLMObsTags.INPUT, inputData);
    }
    if (outputData != null && !outputData.isEmpty()) {
      this.span.setTag(LLMObsTags.LLMOBS_TAG_PREFIX + LLMObsTags.OUTPUT, outputData);
    }
  }

  @Override
  public void annotateIO(String inputData, String outputData) {
    if (finished) {
      return;
    }
    if (inputData != null && !inputData.isEmpty()) {
      this.span.setTag(LLMObsTags.LLMOBS_TAG_PREFIX + LLMObsTags.INPUT, inputData);
    }
    if (outputData != null && !outputData.isEmpty()) {
      this.span.setTag(LLMObsTags.LLMOBS_TAG_PREFIX + LLMObsTags.OUTPUT, outputData);
    }
  }

  @Override
  public void setMetadata(Map<String, Object> metadata) {
    if (finished) {
      return;
    }
    this.span.setTag(LLMObsTags.LLMOBS_TAG_PREFIX + LLMObsTags.METADATA, metadata);
  }

  @Override
  public void setMetrics(Map<String, Number> metrics) {
    if (finished) {
      return;
    }
    for (Map.Entry<String, Number> entry : metrics.entrySet()) {
      this.span.setMetric(
          LLMObsTags.LLMOBS_METRIC_PREFIX + entry.getKey(), entry.getValue().doubleValue());
    }
  }

  @Override
  public void setMetric(CharSequence key, int value) {
    if (finished) {
      return;
    }
    this.span.setMetric(LLMObsTags.LLMOBS_METRIC_PREFIX + key, value);
  }

  @Override
  public void setMetric(CharSequence key, long value) {
    if (finished) {
      return;
    }
    this.span.setMetric(LLMObsTags.LLMOBS_METRIC_PREFIX + key, value);
  }

  @Override
  public void setMetric(CharSequence key, double value) {
    if (finished) {
      return;
    }
    this.span.setMetric(LLMObsTags.LLMOBS_METRIC_PREFIX + key, value);
  }

  @Override
  public void setTags(Map<String, Object> tags) {
    if (finished) {
      return;
    }
    if (tags != null && !tags.isEmpty()) {
      for (Map.Entry<String, Object> entry : tags.entrySet()) {
        this.span.setTag(LLMObsTags.LLMOBS_TAG_PREFIX + entry.getKey(), entry.getValue());
      }
    }
  }

  @Override
  public void setTag(String key, String value) {
    if (finished) {
      return;
    }
    this.span.setTag(LLMObsTags.LLMOBS_TAG_PREFIX + key, value);
  }

  @Override
  public void setTag(String key, boolean value) {
    if (finished) {
      return;
    }
    this.span.setTag(LLMObsTags.LLMOBS_TAG_PREFIX + key, value);
  }

  @Override
  public void setTag(String key, int value) {
    if (finished) {
      return;
    }
    this.span.setTag(LLMObsTags.LLMOBS_TAG_PREFIX + key, value);
  }

  @Override
  public void setTag(String key, long value) {
    if (finished) {
      return;
    }
    this.span.setTag(LLMObsTags.LLMOBS_TAG_PREFIX + key, value);
  }

  @Override
  public void setTag(String key, double value) {
    if (finished) {
      return;
    }
    this.span.setTag(LLMObsTags.LLMOBS_TAG_PREFIX + key, value);
  }

  @Override
  public void setError(boolean error) {
    if (finished) {
      return;
    }
    this.span.setError(error);
  }

  @Override
  public void setErrorMessage(String errorMessage) {
    if (finished) {
      return;
    }
    if (errorMessage == null || errorMessage.isEmpty()) {
      return;
    }
    this.span.setError(true);
    this.span.setErrorMessage(errorMessage);
  }

  @Override
  public void addThrowable(Throwable throwable) {
    if (finished) {
      return;
    }
    if (throwable == null) {
      return;
    }
    this.span.setError(true);
    this.span.addThrowable(throwable);
  }

  @Override
  public void finish() {
    this.span.finish();
    this.finished = true;
  }
}
