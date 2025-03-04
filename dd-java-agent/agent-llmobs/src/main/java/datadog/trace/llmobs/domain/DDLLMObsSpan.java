package datadog.trace.llmobs.domain;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.llmobs.LLMObsSpan;
import datadog.trace.api.llmobs.LLMObsTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import datadog.trace.llmobs.LLMObsServices;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DDLLMObsSpan implements LLMObsSpan {

  private enum State {
    VALID,
    INVALID_IO_MESSAGE_KEY
  }

  private static final String MESSAGE_KEY_ROLE = "role";
  private static final String MESSAGE_KEY_CONTENT = "content";

  private static final Set<String> VALID_MESSAGE_KEYS = new HashSet<>(Arrays.asList(MESSAGE_KEY_ROLE, MESSAGE_KEY_CONTENT));

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

  private static final String LLM_OBS_INSTRUMENTATION_NAME = "llmobs";

  private static final String PARENT_ID_TAG_INTERNAL = "parent_id";

  private static final Logger LOGGER = LoggerFactory.getLogger(DDLLMObsSpan.class);

  private final AgentSpan span;
  private final String spanKind;

  private boolean finished = false;

  private final LLMObsServices llmObsServices;

  public DDLLMObsSpan(
      @Nonnull String kind,
      String spanName,
      @Nonnull String mlApp,
      String sessionID,
      @Nonnull String serviceName,
      @Nonnull LLMObsServices llmObsServices) {

    if (null == spanName || spanName.isEmpty()) {
      spanName = kind;
    }

    this.llmObsServices = llmObsServices;

    SpanContextInfo activeSpanCtxInfo = this.llmObsServices.getActiveSpanContext();

    AgentTracer.SpanBuilder spanBuilder =
        AgentTracer.get()
            .buildSpan(LLM_OBS_INSTRUMENTATION_NAME, spanName)
            .withServiceName(serviceName)
            .withSpanType(DDSpanTypes.LLMOBS);

    AgentSpanContext activeCtx = activeSpanCtxInfo.getActiveContext();
    if (!activeSpanCtxInfo.isRoot() && null != activeCtx) {
      spanBuilder.asChildOf(activeCtx);
    }

    this.span = spanBuilder.start();
    this.span.setTag(SPAN_KIND, kind);
    this.spanKind = kind;
    this.span.setTag(LLMOBS_TAG_PREFIX + LLMObsTags.ML_APP, mlApp);
    this.span.setTag(LLMOBS_TAG_PREFIX + PARENT_ID_TAG_INTERNAL, activeSpanCtxInfo.getParentSpanID());
    if (sessionID != null && !sessionID.isEmpty()) {
      this.span.setTag(LLMOBS_TAG_PREFIX + LLMObsTags.SESSION_ID, sessionID);
    }

    this.llmObsServices.setActiveSpanContext(new SpanContextInfo(span.context(), String.valueOf(span.context().getSpanId())));
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

  private static State validateIOMessages(List<Map<String, Object>> messages) {
    for (Map<String, Object> message : messages) {
      for (String key : message.keySet()) {
        if (!VALID_MESSAGE_KEYS.contains(key)) {
          return State.INVALID_IO_MESSAGE_KEY;
        }
      }
    }
    return State.VALID;
  }

  @Override
  public void annotateIO(
      List<Map<String, Object>> inputData, List<Map<String, Object>> outputData) {
    if (finished) {
      return;
    }
    LOGGER.warn("ANNOTATE IN {} OUT {}", inputData, outputData);
    if (inputData != null && !inputData.isEmpty()) {
      State inputState = validateIOMessages(inputData);
      if (validateIOMessages(inputData) != State.VALID) {
        LOGGER.debug("malformed/unexpected input message, state={}", inputState);
      }
      this.span.setTag(INPUT, inputData);
    }
    if (outputData != null && !outputData.isEmpty()) {
      State outputState = validateIOMessages(outputData);
      if (validateIOMessages(outputData) != State.VALID) {
        LOGGER.debug("malformed/unexpected output message, state={}", outputState);
      }
      this.span.setTag(OUTPUT, outputData);
    }
  }

  @Override
  public void annotateIO(String inputData, String outputData) {
    if (finished) {
      return;
    }
    if (inputData != null && !inputData.isEmpty()) {
      if (Tags.LLMOBS_LLM_SPAN_KIND.equals(this.spanKind)) {
        annotateIO(Collections.singletonList(Collections.singletonMap(MESSAGE_KEY_CONTENT, inputData)), null);
      } else {
        this.span.setTag(INPUT, inputData);
      }
    }
    if (outputData != null && !outputData.isEmpty()) {
      if (Tags.LLMOBS_LLM_SPAN_KIND.equals(this.spanKind)) {
        annotateIO(null, Collections.singletonList(Collections.singletonMap(MESSAGE_KEY_CONTENT, outputData)));
      } else {
        this.span.setTag(OUTPUT, outputData);
      }
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
      LOGGER.debug("unexpected instance type for metadata {}, overwriting for now", value.getClass().getName());
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
    this.finished = true;
    this.llmObsServices.removeActiveSpanContext();
  }
}
