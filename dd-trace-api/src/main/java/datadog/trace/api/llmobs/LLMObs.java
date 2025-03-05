package datadog.trace.api.llmobs;

import datadog.trace.api.llmobs.noop.NoOpLLMObsEvalProcessor;
import datadog.trace.api.llmobs.noop.NoOpLLMObsSpanFactory;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

public class LLMObs {
  protected LLMObs() {}

  protected static LLMObsSpanFactory SPAN_FACTORY = NoOpLLMObsSpanFactory.INSTANCE;
  protected static LLMObsEvalProcessor EVAL_PROCESSOR = NoOpLLMObsEvalProcessor.INSTANCE;

  public static LLMObsSpan startLLMSpan(
      String spanName,
      String modelName,
      String modelProvider,
      @Nullable String mlApp,
      @Nullable String sessionID) {

    return SPAN_FACTORY.startLLMSpan(spanName, modelName, modelProvider, mlApp, sessionID);
  }

  public static LLMObsSpan startAgentSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionID) {

    return SPAN_FACTORY.startAgentSpan(spanName, mlApp, sessionID);
  }

  public static LLMObsSpan startToolSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionID) {

    return SPAN_FACTORY.startToolSpan(spanName, mlApp, sessionID);
  }

  public static LLMObsSpan startTaskSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionID) {

    return SPAN_FACTORY.startTaskSpan(spanName, mlApp, sessionID);
  }

  public static LLMObsSpan startWorkflowSpan(
      String spanName, @Nullable String mlApp, @Nullable String sessionID) {

    return SPAN_FACTORY.startWorkflowSpan(spanName, mlApp, sessionID);
  }

  public static void SubmitEvaluation(LLMObsSpan llmObsSpan, String label, String categoricalValue, Map<String, Object> tags) {}

  public static void SubmitEvaluation(LLMObsSpan llmObsSpan, String label, double numericalValue, Map<String, Object> tags) {}

  public interface LLMObsSpanFactory {
    LLMObsSpan startLLMSpan(
        String spanName,
        String modelName,
        String modelProvider,
        @Nullable String mlApp,
        @Nullable String sessionID);

    LLMObsSpan startAgentSpan(String spanName, @Nullable String mlApp, @Nullable String sessionID);

    LLMObsSpan startToolSpan(String spanName, @Nullable String mlApp, @Nullable String sessionID);

    LLMObsSpan startTaskSpan(String spanName, @Nullable String mlApp, @Nullable String sessionID);

    LLMObsSpan startWorkflowSpan(
        String spanName, @Nullable String mlApp, @Nullable String sessionID);
  }

  public interface LLMObsEvalProcessor {
    void SubmitEvaluation(LLMObsSpan llmObsSpan, String label, double numericalValue, Map<String, Object> tags);
    void SubmitEvaluation(LLMObsSpan llmObsSpan, String label, String categoricalValue, Map<String, Object> tags);
  }

  public static class ToolCall {
    private String name;
    private String type;
    private String toolID;
    private Map<String, Object> arguments;

    public static ToolCall from(
        String name, String type, String toolID, Map<String, Object> arguments) {
      return new ToolCall(name, type, toolID, arguments);
    }

    private ToolCall(String name, String type, String toolID, Map<String, Object> arguments) {
      this.name = name;
      this.type = type;
      this.toolID = toolID;
      this.arguments = arguments;
    }

    public String getName() {
      return name;
    }

    public String getType() {
      return type;
    }

    public String getToolID() {
      return toolID;
    }

    public Map<String, Object> getArguments() {
      return arguments;
    }
  }

  public static class LLMMessage {
    private String role;
    private String content;
    private List<ToolCall> toolCalls;

    public static LLMMessage from(String role, String content, List<ToolCall> toolCalls) {
      return new LLMMessage(role, content, toolCalls);
    }

    public static LLMMessage from(String role, String content) {
      return new LLMMessage(role, content);
    }

    private LLMMessage(String role, String content, List<ToolCall> toolCalls) {
      this.role = role;
      this.content = content;
      this.toolCalls = toolCalls;
    }

    private LLMMessage(String role, String content) {
      this.role = role;
      this.content = content;
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
  }
}
