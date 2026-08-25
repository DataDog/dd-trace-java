package datadog.trace.api.llmobs;

import datadog.trace.api.llmobs.noop.NoOpLLMObsEvalProcessor;
import datadog.trace.api.llmobs.noop.NoOpLLMObsFeedbackProcessor;
import datadog.trace.api.llmobs.noop.NoOpLLMObsSpanFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class LLMObs {
  protected LLMObs() {}

  protected static LLMObsSpanFactory SPAN_FACTORY = NoOpLLMObsSpanFactory.INSTANCE;
  protected static LLMObsEvalProcessor EVAL_PROCESSOR = NoOpLLMObsEvalProcessor.INSTANCE;
  private static final Object SPAN_PROCESSOR_LOCK = new Object();
  @Nullable protected static volatile LLMObsSpanProcessor SPAN_PROCESSOR;
  protected static LLMObsFeedbackProcessor FEEDBACK_PROCESSOR =
      NoOpLLMObsFeedbackProcessor.INSTANCE;

  public static LLMObsSpan startLLMSpan(
      String spanName,
      String modelName,
      String modelProvider,
      @Nullable String mlApp,
      @Nullable String sessionId) {

    return SPAN_FACTORY.startLLMSpan(spanName, modelName, modelProvider, mlApp, sessionId);
  }

  public static LLMObsSpan startAgentSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionId) {

    return SPAN_FACTORY.startAgentSpan(spanName, mlApp, sessionId);
  }

  /**
   * Starts an agent span, optionally tagging it with a version.
   *
   * <p>The version is set as an {@code agent_version} tag on this span and propagated to every
   * descendant LLMObs span started under it.
   *
   * <p>Propagation only happens via this {@code version} parameter, evaluated once when this span
   * is created. Calling {@link LLMObsSpan#setTag} with {@code agent_version} afterwards — including
   * on the span returned here — only sets that span's own tag and does not propagate to
   * descendants.
   *
   * @param version the version of this agent, or {@code null}/empty to leave it untagged
   */
  public static LLMObsSpan startAgentSpan(
      String spanName,
      @Nullable String mlApp,
      @Nullable String sessionId,
      @Nullable String version) {

    return SPAN_FACTORY.startAgentSpan(spanName, mlApp, sessionId, version);
  }

  public static LLMObsSpan startToolSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionId) {

    return SPAN_FACTORY.startToolSpan(spanName, mlApp, sessionId);
  }

  public static LLMObsSpan startTaskSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionId) {

    return SPAN_FACTORY.startTaskSpan(spanName, mlApp, sessionId);
  }

  public static LLMObsSpan startWorkflowSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionId) {

    return SPAN_FACTORY.startWorkflowSpan(spanName, mlApp, sessionId);
  }

  public static LLMObsSpan startEmbeddingSpan(
      String spanName,
      @Nullable String mlApp,
      @Nullable String modelProvider,
      @Nullable String modelName,
      @Nullable String sessionId) {
    return SPAN_FACTORY.startEmbeddingSpan(spanName, mlApp, modelProvider, modelName, sessionId);
  }

  public static LLMObsSpan startRetrievalSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionId) {
    return SPAN_FACTORY.startRetrievalSpan(spanName, mlApp, sessionId);
  }

  /**
   * Registers a processor to be called for each LLM Observability span before it is sent.
   *
   * <p>The processor can modify the span input and output, or return {@code null} to omit the span
   * from LLM Observability. Only one processor can be registered at a time.
   *
   * @param processor the processor to register
   * @throws NullPointerException if {@code processor} is {@code null}
   * @throws IllegalStateException if a processor is already registered
   */
  public static void registerProcessor(LLMObsSpanProcessor processor) {
    Objects.requireNonNull(processor, "processor");
    synchronized (SPAN_PROCESSOR_LOCK) {
      if (SPAN_PROCESSOR != null) {
        throw new IllegalStateException(
            "An LLM Observability span processor is already registered. "
                + "Deregister it before registering another.");
      }
      SPAN_PROCESSOR = processor;
    }
  }

  /** Deregisters the current LLM Observability span processor, if one is registered. */
  public static void deregisterProcessor() {
    synchronized (SPAN_PROCESSOR_LOCK) {
      SPAN_PROCESSOR = null;
    }
  }

  public static void SubmitEvaluation(
      LLMObsSpan llmObsSpan, String label, String categoricalValue, Map<String, Object> tags) {
    EVAL_PROCESSOR.SubmitEvaluation(llmObsSpan, label, categoricalValue, tags);
  }

  public static void SubmitEvaluation(
      LLMObsSpan llmObsSpan,
      String label,
      String categoricalValue,
      String mlApp,
      Map<String, Object> tags) {
    EVAL_PROCESSOR.SubmitEvaluation(llmObsSpan, label, categoricalValue, mlApp, tags);
  }

  public static void SubmitEvaluation(
      LLMObsSpan llmObsSpan, String label, double scoreValue, Map<String, Object> tags) {
    EVAL_PROCESSOR.SubmitEvaluation(llmObsSpan, label, scoreValue, tags);
  }

  public static void SubmitEvaluation(
      LLMObsSpan llmObsSpan,
      String label,
      double scoreValue,
      String mlApp,
      Map<String, Object> tags) {
    EVAL_PROCESSOR.SubmitEvaluation(llmObsSpan, label, scoreValue, mlApp, tags);
  }

  /**
   * Submits end-user feedback on a span, trace, session or customer-defined join key.
   *
   * <p>Unlike an evaluation, which scores a span from an automated or offline judge, feedback
   * carries the identity of whoever submitted it and can target an entity the submitting process
   * has no Datadog context for.
   *
   * <pre>{@code
   * LLMObs.submitFeedback(
   *     LLMObs.Feedback.builder()
   *         .span(span)
   *         .label("thumbs")
   *         .booleanValue(true)
   *         .submitter("user-123", "end_user")
   *         .assessment(LLMObs.Feedback.Assessment.PASS)
   *         .reasoning("answered the question")
   *         .build());
   * }</pre>
   *
   * <p>This is where the feedback is validated. When LLM Observability is disabled, or the agent is
   * not attached, the call is a no-op and an invalid feedback goes unnoticed rather than breaking
   * the host application.
   *
   * @param feedback the feedback to submit, built with {@link Feedback#builder()}
   * @throws IllegalArgumentException if LLM Observability is enabled and the feedback is invalid,
   *     e.g. no target, no value, no submitter, or a label containing a {@code '.'}
   */
  public static void submitFeedback(Feedback feedback) {
    FEEDBACK_PROCESSOR.submitFeedback(feedback);
  }

  public interface LLMObsSpanFactory {
    LLMObsSpan startLLMSpan(
        String spanName,
        String modelName,
        String modelProvider,
        @Nullable String mlApp,
        @Nullable String sessionId);

    LLMObsSpan startAgentSpan(String spanName, @Nullable String mlApp, @Nullable String sessionId);

    default LLMObsSpan startAgentSpan(
        String spanName,
        @Nullable String mlApp,
        @Nullable String sessionId,
        @Nullable String version) {
      return startAgentSpan(spanName, mlApp, sessionId);
    }

    LLMObsSpan startToolSpan(String spanName, @Nullable String mlApp, @Nullable String sessionId);

    LLMObsSpan startTaskSpan(String spanName, @Nullable String mlApp, @Nullable String sessionId);

    LLMObsSpan startWorkflowSpan(
        String spanName, @Nullable String mlApp, @Nullable String sessionId);

    LLMObsSpan startEmbeddingSpan(
        String spanName,
        @Nullable String mlApp,
        @Nullable String modelProvider,
        @Nullable String modelName,
        @Nullable String sessionId);

    LLMObsSpan startRetrievalSpan(
        String spanName, @Nullable String mlApp, @Nullable String sessionId);
  }

  public interface LLMObsEvalProcessor {
    void SubmitEvaluation(
        LLMObsSpan llmObsSpan, String label, double scoreValue, Map<String, Object> tags);

    void SubmitEvaluation(
        LLMObsSpan llmObsSpan,
        String label,
        double scoreValue,
        String mlApp,
        Map<String, Object> tags);

    void SubmitEvaluation(
        LLMObsSpan llmObsSpan, String label, String categoricalValue, Map<String, Object> tags);

    void SubmitEvaluation(
        LLMObsSpan llmObsSpan,
        String label,
        String categoricalValue,
        String mlApp,
        Map<String, Object> tags);
  }

  public interface LLMObsFeedbackProcessor {
    void submitFeedback(Feedback feedback);
  }

  /**
   * End-user feedback on a span, trace, session or customer-defined join key.
   *
   * <p>Instances are immutable and built through {@link #builder()}. Neither the builder nor {@link
   * Builder#build()} ever throws: the first problem found is recorded and surfaced by {@link
   * #validate()}, which {@link LLMObs#submitFeedback(Feedback)} runs. Validation therefore only
   * fires when LLM Observability is actually enabled, matching dd-trace-py — instrumented code that
   * runs without the agent attached never sees an exception it would not see in production.
   */
  public static class Feedback {

    /** The kind of value carried by a feedback metric. */
    public enum MetricType {
      /** A value from a set of names, e.g. {@code "satisfied"}. */
      CATEGORICAL,
      /** A numeric value. */
      SCORE,
      /** A true/false value, e.g. a thumbs up or down. */
      BOOLEAN,
      /** A structured value. */
      JSON,
      /** Free-form text, e.g. a written comment. Feedback-only; evaluations reject it. */
      TEXT;

      /**
       * Returns the wire representation of this metric type.
       *
       * @return the lower case name, as expected by the intake
       */
      @Override
      public String toString() {
        return name().toLowerCase(Locale.ROOT);
      }
    }

    /** Whether the submitter considered the targeted operation a success. */
    public enum Assessment {
      /** The operation was satisfactory. */
      PASS,
      /** The operation was not satisfactory. */
      FAIL;

      /**
       * Returns the wire representation of this assessment.
       *
       * @return the lower case name, as expected by the intake
       */
      @Override
      public String toString() {
        return name().toLowerCase(Locale.ROOT);
      }
    }

    /** The entity a feedback is attached to. Exactly one is set on a given feedback. */
    public enum TargetType {
      /** A single span. */
      SPAN_ID("span_id"),
      /** A whole trace. */
      TRACE_ID("trace_id"),
      /** A session, spanning several traces. */
      SESSION_ID("session_id"),
      /** A customer-defined business entity key, opaque to the tracer. */
      FEEDBACK_JOIN_KEY("feedback_join_key");

      private final String wireKey;

      TargetType(String wireKey) {
        this.wireKey = wireKey;
      }

      public String getWireKey() {
        return wireKey;
      }
    }

    /** Who submitted a feedback. */
    public static class Submitter {
      @Nullable private final String id;
      @Nullable private final String type;

      /**
       * Creates a submitter. An invalid id is not rejected here but by {@link Feedback#validate()}.
       *
       * @param id the identifier of the submitter, must not be null or empty
       * @param type an optional free-form qualifier, e.g. {@code "end_user"}
       */
      public Submitter(@Nonnull String id, @Nullable String type) {
        this.id = id;
        this.type = type;
      }

      @Nullable
      public String getId() {
        return id;
      }

      @Nullable
      public String getType() {
        return type;
      }
    }

    /**
     * Why a feedback cannot be submitted. The code is a stable, low cardinality identifier reported
     * as telemetry; the message is meant for humans.
     */
    public static final class ValidationError {
      private final String code;
      private final String message;

      private ValidationError(String code, String message) {
        this.code = code;
        this.message = message;
      }

      @Nonnull
      public String getCode() {
        return code;
      }

      @Nonnull
      public String getMessage() {
        return message;
      }
    }

    @Nullable private final TargetType targetType;
    @Nullable private final String targetValue;
    @Nullable private final String label;
    @Nullable private final MetricType metricType;
    @Nullable private final Object value;
    @Nullable private final Submitter submitter;
    @Nullable private final String mlApp;
    @Nullable private final Assessment assessment;
    @Nullable private final String reasoning;
    private final long timestampMs;
    @Nullable private final Map<String, Object> tags;
    @Nullable private final ValidationError validationError;

    private Feedback(Builder builder, long timestampMs, @Nullable ValidationError validationError) {
      this.validationError = validationError;
      this.timestampMs = timestampMs;
      this.targetType = builder.targetType;
      this.targetValue = builder.targetValue;
      this.label = builder.label;
      this.metricType = builder.metricType;
      this.value = builder.value;
      this.submitter = builder.submitter;
      this.mlApp = builder.mlApp;
      this.assessment = builder.assessment;
      this.reasoning = builder.reasoning;
      this.tags =
          builder.tags == null ? null : Collections.unmodifiableMap(new HashMap<>(builder.tags));
    }

    public static Builder builder() {
      return new Builder();
    }

    /**
     * Checks whether this feedback can be submitted. Called by {@link
     * LLMObs#submitFeedback(Feedback)}; the getters below are only guaranteed non-null once it
     * returned null.
     *
     * @return the first problem found while building this feedback, or null if it is valid
     */
    @Nullable
    public ValidationError validate() {
      return validationError;
    }

    @Nullable
    public TargetType getTargetType() {
      return targetType;
    }

    @Nullable
    public String getTargetValue() {
      return targetValue;
    }

    @Nullable
    public String getLabel() {
      return label;
    }

    @Nullable
    public MetricType getMetricType() {
      return metricType;
    }

    /** Returns the feedback value, whose runtime type matches {@link #getMetricType()}. */
    @Nullable
    public Object getValue() {
      return value;
    }

    @Nullable
    public Submitter getSubmitter() {
      return submitter;
    }

    /** Returns the ML app, or null to fall back on the tracer configured one. */
    @Nullable
    public String getMlApp() {
      return mlApp;
    }

    @Nullable
    public Assessment getAssessment() {
      return assessment;
    }

    @Nullable
    public String getReasoning() {
      return reasoning;
    }

    /**
     * Returns the submission time in milliseconds since the epoch. This is the only ordering signal
     * available to the backend when the same feedback is re-submitted with a new value.
     */
    public long getTimestampMs() {
      return timestampMs;
    }

    /** Returns an unmodifiable view of the tags, or null if none were provided. */
    @Nullable
    public Map<String, Object> getTags() {
      return tags;
    }

    /**
     * Builds a {@link Feedback}. Exactly one target and exactly one value must be set; setting
     * either twice, even to the same kind, is rejected so that a silently overwritten target cannot
     * ship.
     *
     * <p>No method on this builder throws. The first problem found is remembered and reported by
     * {@link Feedback#validate()} at submission time.
     */
    public static class Builder {
      private TargetType targetType;
      private String targetValue;
      private String label;
      private MetricType metricType;
      private Object value;
      private Submitter submitter;
      private String mlApp;
      private Assessment assessment;
      private String reasoning;
      private long timestampMs;
      private Map<String, Object> tags;
      private ValidationError error;

      private Builder() {}

      /**
       * Targets the given span. Wire-equivalent to {@link #spanId(String)} with the span's id.
       *
       * @param span the span to attach the feedback to
       * @return this builder
       */
      public Builder span(@Nonnull LLMObsSpan span) {
        if (span == null) {
          return fail("invalid_span", "span must not be null");
        }
        return target(TargetType.SPAN_ID, String.valueOf(span.getSpanId()));
      }

      /**
       * Targets the span with the given id.
       *
       * @param spanId the span identifier
       * @return this builder
       */
      public Builder spanId(@Nonnull String spanId) {
        return target(TargetType.SPAN_ID, spanId);
      }

      /**
       * Targets the trace with the given id.
       *
       * @param traceId the trace identifier
       * @return this builder
       */
      public Builder traceId(@Nonnull String traceId) {
        return target(TargetType.TRACE_ID, traceId);
      }

      /**
       * Targets the session with the given id.
       *
       * @param sessionId the session identifier
       * @return this builder
       */
      public Builder sessionId(@Nonnull String sessionId) {
        return target(TargetType.SESSION_ID, sessionId);
      }

      /**
       * Targets a customer-defined business entity, e.g. {@code "incident-123"}. The key is opaque
       * to the tracer: it is emitted as-is and never matched against any span.
       *
       * @param feedbackJoinKey the business entity key
       * @return this builder
       */
      public Builder feedbackJoinKey(@Nonnull String feedbackJoinKey) {
        return target(TargetType.FEEDBACK_JOIN_KEY, feedbackJoinKey);
      }

      /**
       * Sets the name of the feedback metric, e.g. {@code "thumbs"}.
       *
       * @param label the metric name, must not contain a {@code '.'}
       * @return this builder
       */
      public Builder label(@Nonnull String label) {
        this.label = label;
        return this;
      }

      /**
       * Sets a categorical value, e.g. {@code "satisfied"}.
       *
       * @param value the value
       * @return this builder
       */
      public Builder categoricalValue(@Nonnull String value) {
        return value(MetricType.CATEGORICAL, value);
      }

      /**
       * Sets a numeric value.
       *
       * @param value the value, must be finite as JSON has no representation for NaN nor infinity
       * @return this builder
       */
      public Builder scoreValue(double value) {
        if (!Double.isFinite(value)) {
          return fail("invalid_metric_value", "score value must be finite");
        }
        return value(MetricType.SCORE, value);
      }

      /**
       * Sets a true/false value, e.g. a thumbs up or down.
       *
       * @param value the value
       * @return this builder
       */
      public Builder booleanValue(boolean value) {
        return value(MetricType.BOOLEAN, value);
      }

      /**
       * Sets a structured value.
       *
       * @param value the value, serialized as a JSON object
       * @return this builder
       */
      public Builder jsonValue(@Nonnull Map<String, Object> value) {
        // Serialization happens later, on the submission worker, so the caller-owned map is
        // snapshotted here to keep the submitted value stable, the same way tags are.
        return value(
            MetricType.JSON,
            value == null ? null : Collections.unmodifiableMap(new HashMap<>(value)));
      }

      /**
       * Sets a free-form text value, e.g. a written comment.
       *
       * @param value the value
       * @return this builder
       */
      public Builder textValue(@Nonnull String value) {
        return value(MetricType.TEXT, value);
      }

      /**
       * Sets who submitted this feedback.
       *
       * @param id the identifier of the submitter
       * @param type an optional qualifier, e.g. {@code "end_user"}
       * @return this builder
       */
      public Builder submitter(@Nonnull String id, @Nullable String type) {
        this.submitter = new Submitter(id, type);
        return this;
      }

      /**
       * Sets who submitted this feedback.
       *
       * @param submitter the submitter
       * @return this builder
       */
      public Builder submitter(@Nonnull Submitter submitter) {
        this.submitter = submitter;
        return this;
      }

      /**
       * Overrides the ML application this feedback belongs to.
       *
       * @param mlApp the ML app; when null or empty the tracer configured one is used
       * @return this builder
       */
      public Builder mlApp(@Nullable String mlApp) {
        this.mlApp = mlApp;
        return this;
      }

      /**
       * Sets whether the submitter considered the targeted operation a success.
       *
       * @param assessment the assessment
       * @return this builder
       */
      public Builder assessment(@Nullable Assessment assessment) {
        this.assessment = assessment;
        return this;
      }

      /**
       * Sets a free-form justification of this feedback.
       *
       * @param reasoning the reasoning
       * @return this builder
       */
      public Builder reasoning(@Nullable String reasoning) {
        this.reasoning = reasoning;
        return this;
      }

      /**
       * Overrides the submission time. Defaults to the time {@link #build()} is called.
       *
       * @param timestampMs the submission time, in milliseconds since the epoch
       * @return this builder
       */
      public Builder timestampMs(long timestampMs) {
        this.timestampMs = timestampMs;
        return this;
      }

      /**
       * Sets the tags attached to this feedback.
       *
       * @param tags a map of JSON serializable key-value pairs
       * @return this builder
       */
      public Builder tags(@Nullable Map<String, Object> tags) {
        this.tags = tags == null ? null : new HashMap<>(tags);
        return this;
      }

      /**
       * Adds a single tag to this feedback.
       *
       * @param key the tag key
       * @param value the tag value
       * @return this builder
       */
      public Builder tag(@Nonnull String key, @Nonnull Object value) {
        if (this.tags == null) {
          this.tags = new HashMap<>();
        }
        this.tags.put(key, value);
        return this;
      }

      /**
       * Builds the feedback. Never throws: any problem is carried by the returned instance and
       * reported by {@link Feedback#validate()} when it is submitted.
       *
       * @return the built feedback
       */
      public Feedback build() {
        return new Feedback(
            this, timestampMs == 0 ? System.currentTimeMillis() : timestampMs, validationError());
      }

      /**
       * Returns the first problem preventing submission, earlier builder errors taking priority.
       */
      @Nullable
      private ValidationError validationError() {
        if (error != null) {
          return error;
        }
        if (targetType == null) {
          return new ValidationError(
              "invalid_target_count",
              "exactly one of span, spanId, traceId, sessionId or feedbackJoinKey must be specified"
                  + " to submit feedback");
        }
        if (label == null || label.isEmpty()) {
          return new ValidationError(
              "invalid_metric_label", "label must be the specified name of the feedback metric");
        }
        if (label.indexOf('.') >= 0) {
          return new ValidationError("invalid_label_value", "label value must not contain a '.'");
        }
        if (metricType == null) {
          return new ValidationError(
              "invalid_metric_type",
              "exactly one of categoricalValue, scoreValue, booleanValue, jsonValue or textValue"
                  + " must be specified to submit feedback");
        }
        if (submitter == null) {
          return new ValidationError(
              "invalid_submitter", "submitter must be specified to submit feedback");
        }
        if (submitter.getId() == null || submitter.getId().isEmpty()) {
          return new ValidationError(
              "invalid_submitter", "submitter id must be a non-empty string");
        }
        if (timestampMs < 0) {
          return new ValidationError(
              "invalid_timestamp", "timestampMs must be a non-negative long");
        }
        return null;
      }

      private Builder fail(String code, String message) {
        if (this.error == null) {
          this.error = new ValidationError(code, message);
        }
        return this;
      }

      private Builder target(TargetType type, String value) {
        if (targetType != null) {
          return fail(
              "invalid_target_count",
              "a feedback target was already set to "
                  + targetType.getWireKey()
                  + ", exactly one target must be specified");
        }
        if (value == null || value.isEmpty()) {
          return fail(
              "invalid_" + type.getWireKey(), type.getWireKey() + " must be a non-empty string");
        }
        this.targetType = type;
        this.targetValue = value;
        return this;
      }

      private Builder value(MetricType type, Object value) {
        if (metricType != null) {
          return fail(
              "invalid_metric_type",
              "a feedback value was already set as "
                  + metricType
                  + ", exactly one value must be specified");
        }
        if (value == null) {
          return fail("invalid_metric_value", "value must not be null for a " + type + " metric");
        }
        this.metricType = type;
        this.value = value;
        return this;
      }
    }
  }

  /** A prompt template and its associated attributes for an LLM call. */
  public static final class Prompt {
    private final String id;
    private final String version;
    private final String template;
    private final List<LLMMessage> chatTemplate;
    private final Map<String, String> variables;
    private final Map<String, String> tags;
    private final List<String> contextVariables;
    private final List<String> queryVariables;

    public static Builder builder() {
      return new Builder();
    }

    private Prompt(Builder builder) {
      this.id = builder.id;
      this.version = builder.version;
      this.template = builder.template;
      this.chatTemplate = immutableList(builder.chatTemplate);
      this.variables = immutableMap(builder.variables);
      this.tags = immutableMap(builder.tags);
      this.contextVariables = immutableList(builder.contextVariables);
      this.queryVariables = immutableList(builder.queryVariables);
    }

    public String getId() {
      return id;
    }

    public String getVersion() {
      return version;
    }

    public String getTemplate() {
      return template;
    }

    public List<LLMMessage> getChatTemplate() {
      return chatTemplate;
    }

    public Map<String, String> getVariables() {
      return variables;
    }

    public Map<String, String> getTags() {
      return tags;
    }

    public List<String> getContextVariables() {
      return contextVariables;
    }

    public List<String> getQueryVariables() {
      return queryVariables;
    }

    private static <T> List<T> immutableList(List<T> values) {
      return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
      return values == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static final class Builder {
      private String id;
      private String version;
      private String template;
      private List<LLMMessage> chatTemplate;
      private Map<String, String> variables;
      private Map<String, String> tags;
      private List<String> contextVariables;
      private List<String> queryVariables;

      private Builder() {}

      public Builder id(String id) {
        this.id = id;
        return this;
      }

      public Builder version(String version) {
        this.version = version;
        return this;
      }

      public Builder template(String template) {
        this.template = template;
        this.chatTemplate = null;
        return this;
      }

      public Builder template(List<LLMMessage> chatTemplate) {
        this.template = null;
        this.chatTemplate = chatTemplate;
        return this;
      }

      public Builder variables(Map<String, String> variables) {
        this.variables = variables;
        return this;
      }

      public Builder tags(Map<String, String> tags) {
        this.tags = tags;
        return this;
      }

      public Builder contextVariables(List<String> contextVariables) {
        this.contextVariables = contextVariables;
        return this;
      }

      public Builder queryVariables(List<String> queryVariables) {
        this.queryVariables = queryVariables;
        return this;
      }

      public Prompt build() {
        return new Prompt(this);
      }
    }
  }

  public static class ToolCall {
    private String name;
    private String type;
    private String toolId;
    private Map<String, Object> arguments;

    public static ToolCall from(
        String name, String type, String toolId, Map<String, Object> arguments) {
      return new ToolCall(name, type, toolId, arguments);
    }

    private ToolCall(String name, String type, String toolId, Map<String, Object> arguments) {
      this.name = name;
      this.type = type;
      this.toolId = toolId;
      this.arguments = arguments;
    }

    public String getName() {
      return name;
    }

    public String getType() {
      return type;
    }

    public String getToolId() {
      return toolId;
    }

    public Map<String, Object> getArguments() {
      return arguments;
    }
  }

  public static class ToolDefinition {
    private String name;
    private String description;
    private Map<String, Object> schema;
    private String version;

    public static ToolDefinition from(String name) {
      return new ToolDefinition(name, null, null, null);
    }

    public static ToolDefinition from(String name, String description) {
      return new ToolDefinition(name, description, null, null);
    }

    public static ToolDefinition from(String name, String description, Map<String, Object> schema) {
      return new ToolDefinition(name, description, schema, null);
    }

    public static ToolDefinition from(
        String name, String description, Map<String, Object> schema, String version) {
      return new ToolDefinition(name, description, schema, version);
    }

    private ToolDefinition(
        String name, String description, Map<String, Object> schema, String version) {
      this.name = name;
      this.description = description;
      this.schema = schema;
      this.version = version;
    }

    public String getName() {
      return name;
    }

    public String getDescription() {
      return description;
    }

    public Map<String, Object> getSchema() {
      return schema;
    }

    public String getVersion() {
      return version;
    }
  }

  public static class ToolResult {
    private String name;
    private String type;
    private String toolId;
    private String result;

    public static ToolResult from(String name, String type, String toolId, String result) {
      return new ToolResult(name, type, toolId, result);
    }

    private ToolResult(String name, String type, String toolId, String result) {
      this.name = name;
      this.type = type;
      this.toolId = toolId;
      this.result = result;
    }

    public String getName() {
      return name;
    }

    public String getType() {
      return type;
    }

    public String getToolId() {
      return toolId;
    }

    public String getResult() {
      return result;
    }
  }

  public static class LLMMessage {
    private String role;
    private String content;
    private List<ToolCall> toolCalls;
    private List<ToolResult> toolResults;

    public static LLMMessage from(String role, String content, List<ToolCall> toolCalls) {
      return new LLMMessage(role, content, toolCalls, null);
    }

    public static LLMMessage from(
        String role, String content, List<ToolCall> toolCalls, List<ToolResult> toolResults) {
      return new LLMMessage(role, content, toolCalls, toolResults);
    }

    public static LLMMessage from(String role, String content) {
      return new LLMMessage(role, content, null, null);
    }

    public static LLMMessage fromToolResults(String role, List<ToolResult> toolResults) {
      return new LLMMessage(role, null, null, toolResults);
    }

    private LLMMessage(
        String role, String content, List<ToolCall> toolCalls, List<ToolResult> toolResults) {
      this.role = role;
      this.content = content;
      this.toolCalls = toolCalls;
      this.toolResults = toolResults;
    }

    public String getRole() {
      return role;
    }

    public String getContent() {
      return content;
    }

    public List<ToolCall> getToolCalls() {
      return toolCalls;
    }

    public List<ToolResult> getToolResults() {
      return toolResults;
    }
  }

  public static class Document {
    private String text;
    private String name;
    private String id;
    private Double score;

    public static Document from(String text) {
      return new Document(text, null, null, null);
    }

    public static Document from(
        String text, @Nullable String name, @Nullable String id, @Nullable Double score) {
      return new Document(text, name, id, score);
    }

    private Document(String text, String name, String id, Double score) {
      this.text = text;
      this.name = name;
      this.id = id;
      this.score = score;
    }

    public String getText() {
      return text;
    }

    public String getName() {
      return name;
    }

    public String getId() {
      return id;
    }

    public Double getScore() {
      return score;
    }
  }
}
