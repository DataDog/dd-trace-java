package datadog.trace.api.llmobs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.llmobs.noop.NoOpLLMObsEvalProcessor;
import datadog.trace.api.llmobs.noop.NoOpLLMObsSpan;
import datadog.trace.api.llmobs.noop.NoOpLLMObsSpanFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LLMObsTest {

  private static Object originalSpanFactory;
  private static Object originalEvalProcessor;

  @BeforeAll
  static void setupSpec() throws Exception {
    originalSpanFactory = getStaticField("SPAN_FACTORY");
    originalEvalProcessor = getStaticField("EVAL_PROCESSOR");
  }

  @AfterAll
  static void cleanupSpec() throws Exception {
    setStaticField("SPAN_FACTORY", originalSpanFactory);
    setStaticField("EVAL_PROCESSOR", originalEvalProcessor);
  }

  @AfterEach
  void cleanup() throws Exception {
    setStaticField("SPAN_FACTORY", NoOpLLMObsSpanFactory.INSTANCE);
    setStaticField("EVAL_PROCESSOR", NoOpLLMObsEvalProcessor.INSTANCE);
  }

  @Test
  void testToolCallCreationAndGetters() {
    Map<String, Object> arguments = new HashMap<String, Object>();
    arguments.put("location", "New York");
    arguments.put("unit", "celsius");

    LLMObs.ToolCall toolCall =
        LLMObs.ToolCall.from("get_weather", "function", "tool-123", arguments);

    assertEquals("get_weather", toolCall.getName());
    assertEquals("function", toolCall.getType());
    assertEquals("tool-123", toolCall.getToolId());
    assertEquals(arguments, toolCall.getArguments());
  }

  @Test
  void testToolCallWithNullArguments() {
    LLMObs.ToolCall toolCall = LLMObs.ToolCall.from("get_weather", "function", "tool-123", null);

    assertEquals("get_weather", toolCall.getName());
    assertEquals("function", toolCall.getType());
    assertEquals("tool-123", toolCall.getToolId());
    assertNull(toolCall.getArguments());
  }

  @Test
  void testLLMMessageCreationWithToolCalls() {
    Map<String, Object> args = new HashMap<String, Object>();
    args.put("location", "Paris");
    LLMObs.ToolCall toolCall = LLMObs.ToolCall.from("get_weather", "function", "tool-123", args);
    List<LLMObs.ToolCall> toolCalls = new ArrayList<LLMObs.ToolCall>();
    toolCalls.add(toolCall);

    LLMObs.LLMMessage message =
        LLMObs.LLMMessage.from("assistant", "Let me check the weather", toolCalls);

    assertEquals("assistant", message.getRole());
    assertEquals("Let me check the weather", message.getContent());
    assertEquals(toolCalls, message.getToolCalls());
    assertEquals(1, message.getToolCalls().size());
    assertEquals("get_weather", message.getToolCalls().get(0).getName());
    assertEquals("function", message.getToolCalls().get(0).getType());
    assertEquals("tool-123", message.getToolCalls().get(0).getToolId());
    assertEquals(args, message.getToolCalls().get(0).getArguments());
  }

  @Test
  void testLLMMessageCreationWithoutToolCalls() {
    LLMObs.LLMMessage message = LLMObs.LLMMessage.from("user", "What's the weather like?");

    assertEquals("user", message.getRole());
    assertEquals("What's the weather like?", message.getContent());
    assertNull(message.getToolCalls());
  }

  @Test
  void testLLMMessageWithMultipleToolCalls() {
    Map<String, Object> weatherArgs = new HashMap<String, Object>();
    weatherArgs.put("location", "New York");
    LLMObs.ToolCall toolCall1 =
        LLMObs.ToolCall.from("get_weather", "function", "tool-1", weatherArgs);

    Map<String, Object> stockArgs = new HashMap<String, Object>();
    stockArgs.put("symbol", "AAPL");
    LLMObs.ToolCall toolCall2 =
        LLMObs.ToolCall.from("get_stock_price", "function", "tool-2", stockArgs);

    List<LLMObs.ToolCall> toolCalls = Arrays.asList(toolCall1, toolCall2);

    LLMObs.LLMMessage message =
        LLMObs.LLMMessage.from("assistant", "I'll help you with both requests", toolCalls);

    assertEquals("assistant", message.getRole());
    assertEquals("I'll help you with both requests", message.getContent());
    assertEquals(toolCalls, message.getToolCalls());
    assertEquals(2, message.getToolCalls().size());
    assertEquals("get_weather", message.getToolCalls().get(0).getName());
    assertEquals("get_stock_price", message.getToolCalls().get(1).getName());
  }

  @Test
  void testDefaultNoOpSpanFactoryBehavior() {
    LLMObsSpan llmSpan = LLMObs.startLLMSpan("test", "gpt-4", "openai", "app", "session");
    LLMObsSpan agentSpan = LLMObs.startAgentSpan("test", "app", "session");
    LLMObsSpan toolSpan = LLMObs.startToolSpan("test", "app", "session");
    LLMObsSpan taskSpan = LLMObs.startTaskSpan("test", "app", "session");
    LLMObsSpan workflowSpan = LLMObs.startWorkflowSpan("test", "app", "session");
    LLMObsSpan embeddingSpan =
        LLMObs.startEmbeddingSpan("test", "app", "openai", "model", "session");
    LLMObsSpan retrievalSpan = LLMObs.startRetrievalSpan("test", "app", "session");

    assertSame(NoOpLLMObsSpan.INSTANCE, llmSpan);
    assertSame(NoOpLLMObsSpan.INSTANCE, agentSpan);
    assertSame(NoOpLLMObsSpan.INSTANCE, toolSpan);
    assertSame(NoOpLLMObsSpan.INSTANCE, taskSpan);
    assertSame(NoOpLLMObsSpan.INSTANCE, workflowSpan);
    assertSame(NoOpLLMObsSpan.INSTANCE, embeddingSpan);
    assertSame(NoOpLLMObsSpan.INSTANCE, retrievalSpan);
  }

  @Test
  void testSpanCreationWithNullOptionalParameters() {
    LLMObsSpan llmSpan = LLMObs.startLLMSpan("test", "gpt-4", "openai", null, null);
    LLMObsSpan agentSpan = LLMObs.startAgentSpan("test", null, null);
    LLMObsSpan toolSpan = LLMObs.startToolSpan("test", null, null);
    LLMObsSpan taskSpan = LLMObs.startTaskSpan("test", null, null);
    LLMObsSpan workflowSpan = LLMObs.startWorkflowSpan("test", null, null);
    LLMObsSpan embeddingSpan = LLMObs.startEmbeddingSpan("test", null, null, null, null);
    LLMObsSpan retrievalSpan = LLMObs.startRetrievalSpan("test", null, null);

    assertSame(NoOpLLMObsSpan.INSTANCE, llmSpan);
    assertSame(NoOpLLMObsSpan.INSTANCE, agentSpan);
    assertSame(NoOpLLMObsSpan.INSTANCE, toolSpan);
    assertSame(NoOpLLMObsSpan.INSTANCE, taskSpan);
    assertSame(NoOpLLMObsSpan.INSTANCE, workflowSpan);
    assertSame(NoOpLLMObsSpan.INSTANCE, embeddingSpan);
    assertSame(NoOpLLMObsSpan.INSTANCE, retrievalSpan);
  }

  @Test
  void testDefaultNoOpEvaluationProcessorBehavior() {
    assertDoesNotThrow(
        () -> {
          Map<String, Object> emptyTags = new HashMap<String, Object>();
          LLMObs.SubmitEvaluation(NoOpLLMObsSpan.INSTANCE, "label", 0.5, emptyTags);
          LLMObs.SubmitEvaluation(NoOpLLMObsSpan.INSTANCE, "label", 0.5, "app", emptyTags);
          LLMObs.SubmitEvaluation(NoOpLLMObsSpan.INSTANCE, "label", "value", emptyTags);
          LLMObs.SubmitEvaluation(NoOpLLMObsSpan.INSTANCE, "label", "value", "app", emptyTags);
        });
  }

  @Test
  void testEvaluationSubmissionWithVariousScoreValues() {
    LLMObsSpan span = NoOpLLMObsSpan.INSTANCE;
    Map<String, Object> tags = new HashMap<String, Object>();
    tags.put("category", "test");
    tags.put("version", "1.0");

    assertDoesNotThrow(
        () -> {
          LLMObs.SubmitEvaluation(span, "accuracy", 0.0, tags);
          LLMObs.SubmitEvaluation(span, "precision", 1.0, tags);
          LLMObs.SubmitEvaluation(span, "recall", 0.85, tags);
          LLMObs.SubmitEvaluation(span, "f1_score", 0.92, "myapp", tags);
        });
  }

  @Test
  void testEvaluationSubmissionWithCategoricalValues() {
    LLMObsSpan span = NoOpLLMObsSpan.INSTANCE;
    Map<String, Object> tags = new HashMap<String, Object>();
    tags.put("evaluator", "human");
    tags.put("context", "production");

    assertDoesNotThrow(
        () -> {
          LLMObs.SubmitEvaluation(span, "quality", "excellent", tags);
          LLMObs.SubmitEvaluation(span, "relevance", "poor", tags);
          LLMObs.SubmitEvaluation(span, "toxicity", "safe", "content-app", tags);
        });
  }

  @Test
  void testEvaluationSubmissionWithEmptyTags() {
    LLMObsSpan span = NoOpLLMObsSpan.INSTANCE;
    Map<String, Object> emptyTags = new HashMap<String, Object>();

    assertDoesNotThrow(
        () -> {
          LLMObs.SubmitEvaluation(span, "score", 0.75, emptyTags);
          LLMObs.SubmitEvaluation(span, "category", "good", emptyTags);
        });
  }

  @Test
  void testSpanCreationWithCustomFactoryReturnsActualSpans() throws Exception {
    TestSpan llmSpanResult = new TestSpan("llm");
    TestSpan agentSpanResult = new TestSpan("agent");
    TestSpan toolSpanResult = new TestSpan("tool");
    TestSpan taskSpanResult = new TestSpan("task");
    TestSpan workflowSpanResult = new TestSpan("workflow");
    TestSpan embeddingSpanResult = new TestSpan("embedding");
    TestSpan retrievalSpanResult = new TestSpan("retrieval");

    RecordingSpanFactory spanFactory =
        new RecordingSpanFactory(
            llmSpanResult,
            agentSpanResult,
            toolSpanResult,
            taskSpanResult,
            workflowSpanResult,
            embeddingSpanResult,
            retrievalSpanResult);
    RecordingEvalProcessor evalProcessor = new RecordingEvalProcessor();

    setStaticField("SPAN_FACTORY", spanFactory);
    setStaticField("EVAL_PROCESSOR", evalProcessor);

    LLMObsSpan llmSpan =
        LLMObs.startLLMSpan("chat-completion", "gpt-4", "openai", "my-app", "session-1");
    LLMObsSpan agentSpan = LLMObs.startAgentSpan("agent-task", "my-app", "session-1");
    LLMObsSpan toolSpan = LLMObs.startToolSpan("weather-tool", "my-app", "session-1");
    LLMObsSpan taskSpan = LLMObs.startTaskSpan("summarize-task", "my-app", "session-1");
    LLMObsSpan workflowSpan = LLMObs.startWorkflowSpan("data-workflow", "my-app", "session-1");
    LLMObsSpan embeddingSpan =
        LLMObs.startEmbeddingSpan(
            "text-embed", "my-app", "openai", "text-embedding-ada-002", "session-1");
    LLMObsSpan retrievalSpan =
        LLMObs.startRetrievalSpan("document-retrieval", "my-app", "session-1");

    Map<String, Object> scoreTags = new HashMap<String, Object>();
    scoreTags.put("test", "value");
    LLMObs.SubmitEvaluation(llmSpan, "accuracy", 0.95, scoreTags);

    Map<String, Object> categoricalTags = new HashMap<String, Object>();
    categoricalTags.put("reviewer", "human");
    LLMObs.SubmitEvaluation(agentSpan, "quality", "excellent", "eval-app", categoricalTags);

    assertEquals(1, spanFactory.startLLMSpanCalls);
    assertEquals("chat-completion", spanFactory.startLLMSpanName);
    assertEquals("gpt-4", spanFactory.startLLMSpanModelName);
    assertEquals("openai", spanFactory.startLLMSpanModelProvider);
    assertEquals("my-app", spanFactory.startLLMSpanMlApp);
    assertEquals("session-1", spanFactory.startLLMSpanSessionId);

    assertEquals(1, spanFactory.startAgentSpanCalls);
    assertEquals("agent-task", spanFactory.startAgentSpanName);
    assertEquals("my-app", spanFactory.startAgentSpanMlApp);
    assertEquals("session-1", spanFactory.startAgentSpanSessionId);

    assertEquals(1, spanFactory.startToolSpanCalls);
    assertEquals("weather-tool", spanFactory.startToolSpanName);
    assertEquals("my-app", spanFactory.startToolSpanMlApp);
    assertEquals("session-1", spanFactory.startToolSpanSessionId);

    assertEquals(1, spanFactory.startTaskSpanCalls);
    assertEquals("summarize-task", spanFactory.startTaskSpanName);
    assertEquals("my-app", spanFactory.startTaskSpanMlApp);
    assertEquals("session-1", spanFactory.startTaskSpanSessionId);

    assertEquals(1, spanFactory.startWorkflowSpanCalls);
    assertEquals("data-workflow", spanFactory.startWorkflowSpanName);
    assertEquals("my-app", spanFactory.startWorkflowSpanMlApp);
    assertEquals("session-1", spanFactory.startWorkflowSpanSessionId);

    assertEquals(1, spanFactory.startEmbeddingSpanCalls);
    assertEquals("text-embed", spanFactory.startEmbeddingSpanName);
    assertEquals("my-app", spanFactory.startEmbeddingSpanMlApp);
    assertEquals("openai", spanFactory.startEmbeddingSpanModelProvider);
    assertEquals("text-embedding-ada-002", spanFactory.startEmbeddingSpanModelName);
    assertEquals("session-1", spanFactory.startEmbeddingSpanSessionId);

    assertEquals(1, spanFactory.startRetrievalSpanCalls);
    assertEquals("document-retrieval", spanFactory.startRetrievalSpanName);
    assertEquals("my-app", spanFactory.startRetrievalSpanMlApp);
    assertEquals("session-1", spanFactory.startRetrievalSpanSessionId);

    assertEquals(1, evalProcessor.submitScoreWithoutAppCalls);
    assertSame(llmSpanResult, evalProcessor.submitScoreWithoutAppSpan);
    assertEquals("accuracy", evalProcessor.submitScoreWithoutAppLabel);
    assertEquals(0.95, evalProcessor.submitScoreWithoutAppValue, 0.0);
    assertEquals(scoreTags, evalProcessor.submitScoreWithoutAppTags);

    assertEquals(1, evalProcessor.submitCategoricalWithAppCalls);
    assertSame(agentSpanResult, evalProcessor.submitCategoricalWithAppSpan);
    assertEquals("quality", evalProcessor.submitCategoricalWithAppLabel);
    assertEquals("excellent", evalProcessor.submitCategoricalWithAppValue);
    assertEquals("eval-app", evalProcessor.submitCategoricalWithAppMlApp);
    assertEquals(categoricalTags, evalProcessor.submitCategoricalWithAppTags);

    assertSame(llmSpanResult, llmSpan);
    assertSame(agentSpanResult, agentSpan);
    assertSame(toolSpanResult, toolSpan);
    assertSame(taskSpanResult, taskSpan);
    assertSame(workflowSpanResult, workflowSpan);
    assertSame(embeddingSpanResult, embeddingSpan);
    assertSame(retrievalSpanResult, retrievalSpan);

    assertNotSame(NoOpLLMObsSpan.INSTANCE, llmSpan);
    assertNotSame(NoOpLLMObsSpan.INSTANCE, agentSpan);
    assertNotSame(NoOpLLMObsSpan.INSTANCE, toolSpan);
    assertNotSame(NoOpLLMObsSpan.INSTANCE, taskSpan);
    assertNotSame(NoOpLLMObsSpan.INSTANCE, workflowSpan);
    assertNotSame(NoOpLLMObsSpan.INSTANCE, embeddingSpan);
    assertNotSame(NoOpLLMObsSpan.INSTANCE, retrievalSpan);
  }

  @Test
  void testSpanCreationWithNullParametersUsingCustomFactory() throws Exception {
    TestSpan mockSpan = new TestSpan("shared");
    RecordingSpanFactory spanFactory =
        new RecordingSpanFactory(
            mockSpan, mockSpan, mockSpan, mockSpan, mockSpan, mockSpan, mockSpan);

    setStaticField("SPAN_FACTORY", spanFactory);

    LLMObsSpan llmSpan = LLMObs.startLLMSpan("test-span", "gpt-4", "openai", null, null);
    LLMObsSpan embeddingSpan = LLMObs.startEmbeddingSpan("embed-span", null, null, null, null);

    assertEquals(1, spanFactory.startLLMSpanCalls);
    assertEquals("test-span", spanFactory.startLLMSpanName);
    assertEquals("gpt-4", spanFactory.startLLMSpanModelName);
    assertEquals("openai", spanFactory.startLLMSpanModelProvider);
    assertNull(spanFactory.startLLMSpanMlApp);
    assertNull(spanFactory.startLLMSpanSessionId);

    assertEquals(1, spanFactory.startEmbeddingSpanCalls);
    assertEquals("embed-span", spanFactory.startEmbeddingSpanName);
    assertNull(spanFactory.startEmbeddingSpanMlApp);
    assertNull(spanFactory.startEmbeddingSpanModelProvider);
    assertNull(spanFactory.startEmbeddingSpanModelName);
    assertNull(spanFactory.startEmbeddingSpanSessionId);

    assertSame(mockSpan, llmSpan);
    assertSame(mockSpan, embeddingSpan);
  }

  private static void setStaticField(String fieldName, Object value) throws Exception {
    Field field = LLMObs.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(null, value);
  }

  private static Object getStaticField(String fieldName) throws Exception {
    Field field = LLMObs.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(null);
  }

  private static class RecordingSpanFactory implements LLMObs.LLMObsSpanFactory {
    private final LLMObsSpan llmSpan;
    private final LLMObsSpan agentSpan;
    private final LLMObsSpan toolSpan;
    private final LLMObsSpan taskSpan;
    private final LLMObsSpan workflowSpan;
    private final LLMObsSpan embeddingSpan;
    private final LLMObsSpan retrievalSpan;

    private int startLLMSpanCalls;
    private String startLLMSpanName;
    private String startLLMSpanModelName;
    private String startLLMSpanModelProvider;
    private String startLLMSpanMlApp;
    private String startLLMSpanSessionId;

    private int startAgentSpanCalls;
    private String startAgentSpanName;
    private String startAgentSpanMlApp;
    private String startAgentSpanSessionId;

    private int startToolSpanCalls;
    private String startToolSpanName;
    private String startToolSpanMlApp;
    private String startToolSpanSessionId;

    private int startTaskSpanCalls;
    private String startTaskSpanName;
    private String startTaskSpanMlApp;
    private String startTaskSpanSessionId;

    private int startWorkflowSpanCalls;
    private String startWorkflowSpanName;
    private String startWorkflowSpanMlApp;
    private String startWorkflowSpanSessionId;

    private int startEmbeddingSpanCalls;
    private String startEmbeddingSpanName;
    private String startEmbeddingSpanMlApp;
    private String startEmbeddingSpanModelProvider;
    private String startEmbeddingSpanModelName;
    private String startEmbeddingSpanSessionId;

    private int startRetrievalSpanCalls;
    private String startRetrievalSpanName;
    private String startRetrievalSpanMlApp;
    private String startRetrievalSpanSessionId;

    private RecordingSpanFactory(
        LLMObsSpan llmSpan,
        LLMObsSpan agentSpan,
        LLMObsSpan toolSpan,
        LLMObsSpan taskSpan,
        LLMObsSpan workflowSpan,
        LLMObsSpan embeddingSpan,
        LLMObsSpan retrievalSpan) {
      this.llmSpan = llmSpan;
      this.agentSpan = agentSpan;
      this.toolSpan = toolSpan;
      this.taskSpan = taskSpan;
      this.workflowSpan = workflowSpan;
      this.embeddingSpan = embeddingSpan;
      this.retrievalSpan = retrievalSpan;
    }

    @Override
    public LLMObsSpan startLLMSpan(
        String spanName, String modelName, String modelProvider, String mlApp, String sessionId) {
      startLLMSpanCalls++;
      startLLMSpanName = spanName;
      startLLMSpanModelName = modelName;
      startLLMSpanModelProvider = modelProvider;
      startLLMSpanMlApp = mlApp;
      startLLMSpanSessionId = sessionId;
      return llmSpan;
    }

    @Override
    public LLMObsSpan startAgentSpan(String spanName, String mlApp, String sessionId) {
      startAgentSpanCalls++;
      startAgentSpanName = spanName;
      startAgentSpanMlApp = mlApp;
      startAgentSpanSessionId = sessionId;
      return agentSpan;
    }

    @Override
    public LLMObsSpan startToolSpan(String spanName, String mlApp, String sessionId) {
      startToolSpanCalls++;
      startToolSpanName = spanName;
      startToolSpanMlApp = mlApp;
      startToolSpanSessionId = sessionId;
      return toolSpan;
    }

    @Override
    public LLMObsSpan startTaskSpan(String spanName, String mlApp, String sessionId) {
      startTaskSpanCalls++;
      startTaskSpanName = spanName;
      startTaskSpanMlApp = mlApp;
      startTaskSpanSessionId = sessionId;
      return taskSpan;
    }

    @Override
    public LLMObsSpan startWorkflowSpan(String spanName, String mlApp, String sessionId) {
      startWorkflowSpanCalls++;
      startWorkflowSpanName = spanName;
      startWorkflowSpanMlApp = mlApp;
      startWorkflowSpanSessionId = sessionId;
      return workflowSpan;
    }

    @Override
    public LLMObsSpan startEmbeddingSpan(
        String spanName, String mlApp, String modelProvider, String modelName, String sessionId) {
      startEmbeddingSpanCalls++;
      startEmbeddingSpanName = spanName;
      startEmbeddingSpanMlApp = mlApp;
      startEmbeddingSpanModelProvider = modelProvider;
      startEmbeddingSpanModelName = modelName;
      startEmbeddingSpanSessionId = sessionId;
      return embeddingSpan;
    }

    @Override
    public LLMObsSpan startRetrievalSpan(String spanName, String mlApp, String sessionId) {
      startRetrievalSpanCalls++;
      startRetrievalSpanName = spanName;
      startRetrievalSpanMlApp = mlApp;
      startRetrievalSpanSessionId = sessionId;
      return retrievalSpan;
    }
  }

  private static class RecordingEvalProcessor implements LLMObs.LLMObsEvalProcessor {
    private int submitScoreWithoutAppCalls;
    private LLMObsSpan submitScoreWithoutAppSpan;
    private String submitScoreWithoutAppLabel;
    private double submitScoreWithoutAppValue;
    private Map<String, Object> submitScoreWithoutAppTags;

    private int submitCategoricalWithAppCalls;
    private LLMObsSpan submitCategoricalWithAppSpan;
    private String submitCategoricalWithAppLabel;
    private String submitCategoricalWithAppValue;
    private String submitCategoricalWithAppMlApp;
    private Map<String, Object> submitCategoricalWithAppTags;

    @Override
    public void SubmitEvaluation(
        LLMObsSpan llmObsSpan, String label, double scoreValue, Map<String, Object> tags) {
      submitScoreWithoutAppCalls++;
      submitScoreWithoutAppSpan = llmObsSpan;
      submitScoreWithoutAppLabel = label;
      submitScoreWithoutAppValue = scoreValue;
      submitScoreWithoutAppTags = tags;
    }

    @Override
    public void SubmitEvaluation(
        LLMObsSpan llmObsSpan,
        String label,
        double scoreValue,
        String mlApp,
        Map<String, Object> tags) {}

    @Override
    public void SubmitEvaluation(
        LLMObsSpan llmObsSpan, String label, String categoricalValue, Map<String, Object> tags) {}

    @Override
    public void SubmitEvaluation(
        LLMObsSpan llmObsSpan,
        String label,
        String categoricalValue,
        String mlApp,
        Map<String, Object> tags) {
      submitCategoricalWithAppCalls++;
      submitCategoricalWithAppSpan = llmObsSpan;
      submitCategoricalWithAppLabel = label;
      submitCategoricalWithAppValue = categoricalValue;
      submitCategoricalWithAppMlApp = mlApp;
      submitCategoricalWithAppTags = tags;
    }
  }

  private static class TestSpan implements LLMObsSpan {
    private final String name;

    private TestSpan(String name) {
      this.name = name;
    }

    @Override
    public void annotateIO(
        List<LLMObs.LLMMessage> inputMessages, List<LLMObs.LLMMessage> outputMessages) {}

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

    @Override
    public String toString() {
      return "TestSpan{" + "name='" + name + '\'' + '}';
    }
  }
}
