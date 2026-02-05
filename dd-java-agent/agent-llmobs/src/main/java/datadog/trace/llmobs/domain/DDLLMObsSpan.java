package datadog.trace.llmobs.domain;

import datadog.context.ContextScope;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsContext;
import datadog.trace.api.llmobs.LLMObsSpan;
import datadog.trace.api.llmobs.LLMObsTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DDLLMObsSpan implements LLMObsSpan {
  private static final String LLM_MESSAGE_UNKNOWN_ROLE = "unknown";

  // Well known tags for LLM obs will be prefixed with _ml_obs_(tags|metrics).
  // Prefix for tags
  private static final String LLMOBS_TAG_PREFIX = "_ml_obs_tag.";
  // Prefix for metrics
  private static final String LLMOBS_METRIC_PREFIX = "_ml_obs_metric.";

  // internal tags to be prefixed
  private static final String INPUT = LLMOBS_TAG_PREFIX + "input";
  private static final String OUTPUT = LLMOBS_TAG_PREFIX + "output";
  private static final String SPAN_KIND = LLMOBS_TAG_PREFIX + Tags.SPAN_KIND;
  private static final String METADATA = LLMOBS_TAG_PREFIX + LLMObsTags.METADATA;
  private static final String PARENT_ID_TAG_INTERNAL = "parent_id";

  private static final String SERVICE = LLMOBS_TAG_PREFIX + "service";
  private static final String VERSION = LLMOBS_TAG_PREFIX + "version";
  private static final String ENV = LLMOBS_TAG_PREFIX + "env";

  private static final String LLM_OBS_INSTRUMENTATION_NAME = "llmobs";

  private static final Logger LOGGER = LoggerFactory.getLogger(DDLLMObsSpan.class);

  private final AgentSpan span;
  private final String spanKind;
  private final ContextScope scope;

  private boolean finished = false;

  public DDLLMObsSpan(
      @Nonnull String kind,
      String spanName,
      @Nonnull String mlApp,
      String sessionId,
      @Nonnull String serviceName,
      WellKnownTags wellKnownTags) {

    if (null == spanName || spanName.isEmpty()) {
      spanName = kind;
    }

    AgentTracer.SpanBuilder spanBuilder =
        AgentTracer.get()
            .buildSpan(LLM_OBS_INSTRUMENTATION_NAME, spanName)
            .withServiceName(serviceName)
            .withSpanType(DDSpanTypes.LLMOBS);

    this.span = spanBuilder.start();

    // set UST (unified service tags, env, service, version)
    this.span.setTag(ENV, wellKnownTags.getEnv());
    this.span.setTag(SERVICE, wellKnownTags.getService());
    this.span.setTag(VERSION, wellKnownTags.getVersion());

    this.span.setTag(SPAN_KIND, kind);
    this.spanKind = kind;
    this.span.setTag(LLMOBS_TAG_PREFIX + LLMObsTags.ML_APP, mlApp);
    if (sessionId != null && !sessionId.isEmpty()) {
      this.span.setTag(LLMOBS_TAG_PREFIX + LLMObsTags.SESSION_ID, sessionId);
    }

    AgentSpanContext parent = LLMObsContext.current();
    String parentSpanID = LLMObsContext.ROOT_SPAN_ID;
    if (null != parent) {
      if (parent.getTraceId() != this.span.getTraceId()) {
        LOGGER.error(
            "trace ID mismatch, retrieved parent from context trace_id={}, span_id={}, started span trace_id={}, span_id={}",
            parent.getTraceId(),
            parent.getSpanId(),
            this.span.getTraceId(),
            this.span.getSpanId());
      } else {
        parentSpanID = String.valueOf(parent.getSpanId());
      }
    }
    this.span.setTag(LLMOBS_TAG_PREFIX + PARENT_ID_TAG_INTERNAL, parentSpanID);
    this.scope = LLMObsContext.attach(this.span.context());
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
        + this.span.getTag(SPAN_KIND);
  }

  @Override
  public void annotateIO(List<LLMObs.LLMMessage> inputData, List<LLMObs.LLMMessage> outputData) {
    if (finished) {
      return;
    }
    if (inputData != null && !inputData.isEmpty()) {
      this.span.setTag(INPUT, inputData);
    }
    if (outputData != null && !outputData.isEmpty()) {
      this.span.setTag(OUTPUT, outputData);
    }
  }

  @Override
  public void annotateIO(String inputData, String outputData) {
    if (finished) {
      return;
    }
    boolean wrongSpanKind = false;
    if (inputData != null && !inputData.isEmpty()) {
      if (Tags.LLMOBS_LLM_SPAN_KIND.equals(this.spanKind)) {
        wrongSpanKind = true;
        annotateIO(
            Collections.singletonList(LLMObs.LLMMessage.from(LLM_MESSAGE_UNKNOWN_ROLE, inputData)),
            null);
      } else {
        this.span.setTag(INPUT, inputData);
      }
    }
    if (outputData != null && !outputData.isEmpty()) {
      if (Tags.LLMOBS_LLM_SPAN_KIND.equals(this.spanKind)) {
        wrongSpanKind = true;
        annotateIO(
            null,
            Collections.singletonList(
                LLMObs.LLMMessage.from(LLM_MESSAGE_UNKNOWN_ROLE, outputData)));
      } else {
        this.span.setTag(OUTPUT, outputData);
      }
    }
    if (wrongSpanKind) {
      LOGGER.warn(
          "the span being annotated is an LLM span, it is recommended to use the overload with List<LLMObs.LLMMessage> as arguments");
    }
  }

  @Override
  public void setMetadata(Map<String, Object> metadata) {
    if (finished) {
      return;
    }
    Object value = span.getTag(METADATA);
    if (value == null) {
      this.span.setTag(METADATA, new HashMap<>(metadata));
      return;
    }

    if (value instanceof Map) {
      ((Map) value).putAll(metadata);
    } else {
      LOGGER.debug(
          "unexpected instance type for metadata {}, overwriting for now",
          value.getClass().getName());
      this.span.setTag(METADATA, new HashMap<>(metadata));
    }
  }

  @Override
  public void setMetrics(Map<String, Number> metrics) {
    if (finished) {
      return;
    }
    for (Map.Entry<String, Number> entry : metrics.entrySet()) {
      this.span.setMetric(LLMOBS_METRIC_PREFIX + entry.getKey(), entry.getValue().doubleValue());
    }
  }

  @Override
  public void setMetric(CharSequence key, int value) {
    if (finished) {
      return;
    }
    this.span.setMetric(LLMOBS_METRIC_PREFIX + key, value);
  }

  @Override
  public void setMetric(CharSequence key, long value) {
    if (finished) {
      return;
    }
    this.span.setMetric(LLMOBS_METRIC_PREFIX + key, value);
  }

  @Override
  public void setMetric(CharSequence key, double value) {
    if (finished) {
      return;
    }
    this.span.setMetric(LLMOBS_METRIC_PREFIX + key, value);
  }

  @Override
  public void setTags(Map<String, Object> tags) {
    if (finished) {
      return;
    }
    if (tags != null && !tags.isEmpty()) {
      for (Map.Entry<String, Object> entry : tags.entrySet()) {
        this.span.setTag(LLMOBS_TAG_PREFIX + entry.getKey(), entry.getValue());
      }
    }
  }

  @Override
  public void setTag(String key, String value) {
    if (finished) {
      return;
    }
    this.span.setTag(LLMOBS_TAG_PREFIX + key, value);
  }

  @Override
  public void setTag(String key, boolean value) {
    if (finished) {
      return;
    }
    this.span.setTag(LLMOBS_TAG_PREFIX + key, value);
  }

  @Override
  public void setTag(String key, int value) {
    if (finished) {
      return;
    }
    this.span.setTag(LLMOBS_TAG_PREFIX + key, value);
  }

  @Override
  public void setTag(String key, long value) {
    if (finished) {
      return;
    }
    this.span.setTag(LLMOBS_TAG_PREFIX + key, value);
  }

  @Override
  public void setTag(String key, double value) {
    if (finished) {
      return;
    }
    this.span.setTag(LLMOBS_TAG_PREFIX + key, value);
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
    if (finished) {
      return;
    }
    this.span.finish();
    this.scope.close();
    this.finished = true;
  }

  @Override
  public DDTraceId getTraceId() {
    return this.span.getTraceId();
  }

  @Override
  public long getSpanId() {
    return this.span.getSpanId();
  }
}
