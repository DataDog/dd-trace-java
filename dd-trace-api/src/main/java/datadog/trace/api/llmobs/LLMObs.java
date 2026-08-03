package datadog.trace.api.llmobs;

import datadog.trace.api.llmobs.noop.NoOpLLMObsEvalProcessor;
import datadog.trace.api.llmobs.noop.NoOpLLMObsFeedbackProcessor;
import datadog.trace.api.llmobs.noop.NoOpLLMObsSpanFactory;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class LLMObs {
  protected LLMObs() {}

  protected static LLMObsSpanFactory SPAN_FACTORY = NoOpLLMObsSpanFactory.INSTANCE;
  protected static LLMObsEvalProcessor EVAL_PROCESSOR = NoOpLLMObsEvalProcessor.INSTANCE;
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
   * @param feedback the feedback to submit, built with {@link Feedback#builder()}
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
   * <p>Instances are immutable and built through {@link #builder()}. The builder validates the
   * feedback as a whole in {@link Builder#build()}, so an invalid combination fails at the call
   * site rather than being silently dropped by the submission worker.
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

      /**
       * Returns the payload field name carrying this target.
       *
       * @return the wire key, e.g. {@code "span_id"}
       */
      public String getWireKey() {
        return wireKey;
      }
    }

    /** Who submitted a feedback. */
    public static class Submitter {
      private final String id;
      @Nullable private final String type;

      /**
       * Creates a submitter.
       *
       * @param id the identifier of the submitter, must not be null or empty
       * @param type an optional free-form qualifier, e.g. {@code "end_user"}
       * @throws IllegalArgumentException if {@code id} is null or empty
       */
      public Submitter(@Nonnull String id, @Nullable String type) {
        if (id == null || id.isEmpty()) {
          throw new IllegalArgumentException("submitter id must be a non-empty string");
        }
        this.id = id;
        this.type = type;
      }

      /**
       * Returns the submitter identifier.
       *
       * @return the identifier, never null nor empty
       */
      @Nonnull
      public String getId() {
        return id;
      }

      /**
       * Returns the submitter qualifier.
       *
       * @return the qualifier, or null if none was provided
       */
      @Nullable
      public String getType() {
        return type;
      }
    }

    private final TargetType targetType;
    private final String targetValue;
    private final String label;
    private final MetricType metricType;
    private final Object value;
    private final Submitter submitter;
    @Nullable private final String mlApp;
    @Nullable private final Assessment assessment;
    @Nullable private final String reasoning;
    private final long timestampMs;
    @Nullable private final Map<String, Object> tags;

    private Feedback(Builder builder, long timestampMs) {
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

    /**
     * Creates a builder for a feedback.
     *
     * @return a new builder
     */
    public static Builder builder() {
      return new Builder();
    }

    /**
     * Returns which kind of entity this feedback targets.
     *
     * @return the target type
     */
    @Nonnull
    public TargetType getTargetType() {
      return targetType;
    }

    /**
     * Returns the identifier of the targeted entity.
     *
     * @return the target value, never null nor empty
     */
    @Nonnull
    public String getTargetValue() {
      return targetValue;
    }

    /**
     * Returns the name of the feedback metric.
     *
     * @return the label, never null nor empty
     */
    @Nonnull
    public String getLabel() {
      return label;
    }

    /**
     * Returns the kind of value this feedback carries.
     *
     * @return the metric type
     */
    @Nonnull
    public MetricType getMetricType() {
      return metricType;
    }

    /**
     * Returns the feedback value. Its runtime type matches {@link #getMetricType()}.
     *
     * @return the value, never null
     */
    @Nonnull
    public Object getValue() {
      return value;
    }

    /**
     * Returns who submitted this feedback.
     *
     * @return the submitter, never null
     */
    @Nonnull
    public Submitter getSubmitter() {
      return submitter;
    }

    /**
     * Returns the ML application this feedback belongs to.
     *
     * @return the ML app, or null to fall back on the tracer configured one
     */
    @Nullable
    public String getMlApp() {
      return mlApp;
    }

    /**
     * Returns whether the submitter considered the targeted operation a success.
     *
     * @return the assessment, or null if none was provided
     */
    @Nullable
    public Assessment getAssessment() {
      return assessment;
    }

    /**
     * Returns the free-form justification of this feedback.
     *
     * @return the reasoning, or null if none was provided
     */
    @Nullable
    public String getReasoning() {
      return reasoning;
    }

    /**
     * Returns when this feedback was submitted. This is the only ordering signal available to the
     * backend when the same feedback is re-submitted with a new value.
     *
     * @return the submission time, in milliseconds since the epoch
     */
    public long getTimestampMs() {
      return timestampMs;
    }

    /**
     * Returns the tags attached to this feedback.
     *
     * @return an unmodifiable view of the tags, or null if none were provided
     */
    @Nullable
    public Map<String, Object> getTags() {
      return tags;
    }

    /**
     * Builds a {@link Feedback}. Exactly one target and exactly one value must be set; setting
     * either twice, even to the same kind, is rejected so that a silently overwritten target cannot
     * ship.
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

      private Builder() {}

      /**
       * Targets the given span. Wire-equivalent to {@link #spanId(String)} with the span's id.
       *
       * @param span the span to attach the feedback to
       * @return this builder
       * @throws IllegalArgumentException if a target was already set, or {@code span} is null
       */
      public Builder span(@Nonnull LLMObsSpan span) {
        if (span == null) {
          throw new IllegalArgumentException("span must not be null");
        }
        return target(TargetType.SPAN_ID, String.valueOf(span.getSpanId()));
      }

      /**
       * Targets the span with the given id.
       *
       * @param spanId the span identifier
       * @return this builder
       * @throws IllegalArgumentException if a target was already set, or the id is null or empty
       */
      public Builder spanId(@Nonnull String spanId) {
        return target(TargetType.SPAN_ID, spanId);
      }

      /**
       * Targets the trace with the given id.
       *
       * @param traceId the trace identifier
       * @return this builder
       * @throws IllegalArgumentException if a target was already set, or the id is null or empty
       */
      public Builder traceId(@Nonnull String traceId) {
        return target(TargetType.TRACE_ID, traceId);
      }

      /**
       * Targets the session with the given id.
       *
       * @param sessionId the session identifier
       * @return this builder
       * @throws IllegalArgumentException if a target was already set, or the id is null or empty
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
       * @throws IllegalArgumentException if a target was already set, or the key is null or empty
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
       * @throws IllegalArgumentException if a value was already set, or the value is null
       */
      public Builder categoricalValue(@Nonnull String value) {
        return value(MetricType.CATEGORICAL, value);
      }

      /**
       * Sets a numeric value.
       *
       * @param value the value
       * @return this builder
       * @throws IllegalArgumentException if a value was already set
       */
      public Builder scoreValue(double value) {
        return value(MetricType.SCORE, value);
      }

      /**
       * Sets a true/false value, e.g. a thumbs up or down.
       *
       * @param value the value
       * @return this builder
       * @throws IllegalArgumentException if a value was already set
       */
      public Builder booleanValue(boolean value) {
        return value(MetricType.BOOLEAN, value);
      }

      /**
       * Sets a structured value.
       *
       * @param value the value, serialized as a JSON object
       * @return this builder
       * @throws IllegalArgumentException if a value was already set, or the value is null
       */
      public Builder jsonValue(@Nonnull Map<String, Object> value) {
        return value(MetricType.JSON, value);
      }

      /**
       * Sets a free-form text value, e.g. a written comment.
       *
       * @param value the value
       * @return this builder
       * @throws IllegalArgumentException if a value was already set, or the value is null
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
       * @throws IllegalArgumentException if {@code id} is null or empty
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
       * Validates and builds the feedback.
       *
       * @return the built feedback
       * @throws IllegalArgumentException if no target, no label, no value or no submitter was set,
       *     if the label contains a {@code '.'}, or if the timestamp is negative
       */
      public Feedback build() {
        if (targetType == null) {
          throw new IllegalArgumentException(
              "exactly one of span, spanId, traceId, sessionId or feedbackJoinKey must be specified"
                  + " to submit feedback");
        }
        if (label == null || label.isEmpty()) {
          throw new IllegalArgumentException(
              "label must be the specified name of the feedback metric");
        }
        if (label.indexOf('.') >= 0) {
          throw new IllegalArgumentException("label must not contain a '.'");
        }
        if (metricType == null) {
          throw new IllegalArgumentException(
              "exactly one of categoricalValue, scoreValue, booleanValue, jsonValue or textValue"
                  + " must be specified to submit feedback");
        }
        if (submitter == null) {
          throw new IllegalArgumentException("submitter must be specified to submit feedback");
        }
        if (timestampMs < 0) {
          throw new IllegalArgumentException("timestampMs must be a non-negative long");
        }
        return new Feedback(this, timestampMs == 0 ? System.currentTimeMillis() : timestampMs);
      }

      private Builder target(TargetType type, String value) {
        if (targetType != null) {
          throw new IllegalArgumentException(
              "a feedback target was already set to "
                  + targetType.getWireKey()
                  + ", exactly one target must be specified");
        }
        if (value == null || value.isEmpty()) {
          throw new IllegalArgumentException(type.getWireKey() + " must be a non-empty string");
        }
        this.targetType = type;
        this.targetValue = value;
        return this;
      }

      private Builder value(MetricType type, Object value) {
        if (metricType != null) {
          throw new IllegalArgumentException(
              "a feedback value was already set as "
                  + metricType
                  + ", exactly one value must be specified");
        }
        if (value == null) {
          throw new IllegalArgumentException("value must not be null for a " + type + " metric");
        }
        this.metricType = type;
        this.value = value;
        return this;
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

    public static Document from(String text) {
      return new Document(text);
    }

    private Document(String text) {
      this.text = text;
    }

    public String getText() {
      return text;
    }
  }
}
