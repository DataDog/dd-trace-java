package datadog.trace.api.llmobs;

import datadog.trace.api.llmobs.noop.NoOpLLMObsEvalProcessor;
import datadog.trace.api.llmobs.noop.NoOpLLMObsSpanFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

public class LLMObs {
  protected LLMObs() {}

  protected static LLMObsSpanFactory SPAN_FACTORY = NoOpLLMObsSpanFactory.INSTANCE;
  protected static LLMObsEvalProcessor EVAL_PROCESSOR = NoOpLLMObsEvalProcessor.INSTANCE;

  /**
   * Returns {@code true} when LLM Observability is active, i.e. the agent has registered a real
   * span factory. Returns {@code false} when LLMObs is not configured, in which case all {@code
   * startXxxSpan} calls are no-ops and no data is collected or exported.
   *
   * <p>Use this check to avoid constructing inputs that would be discarded:
   *
   * <pre>
   *   if (LLMObs.isEnabled()) {
   *     LLMObsSpan span = LLMObs.startLLMSpan(...);
   *   }
   * </pre>
   */
  public static boolean isEnabled() {
    return !(SPAN_FACTORY instanceof NoOpLLMObsSpanFactory);
  }

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
