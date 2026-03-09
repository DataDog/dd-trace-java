package datadog.trace.api.llmobs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.llmobs.noop.NoOpLLMObsEvalProcessor;
import datadog.trace.api.llmobs.noop.NoOpLLMObsSpan;
import datadog.trace.api.llmobs.noop.NoOpLLMObsSpanFactory;
import java.lang.reflect.Field;
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
    Map<String, Object> arguments = new HashMap<>();
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
    Map<String, Object> args = new HashMap<>();
    args.put("location", "Paris");
    LLMObs.ToolCall toolCall = LLMObs.ToolCall.from("get_weather", "function", "tool-123", args);
    List<LLMObs.ToolCall> toolCalls = List.of(toolCall);

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
    Map<String, Object> weatherArgs = new HashMap<>();
    weatherArgs.put("location", "New York");
    LLMObs.ToolCall toolCall1 =
        LLMObs.ToolCall.from("get_weather", "function", "tool-1", weatherArgs);

    Map<String, Object> stockArgs = new HashMap<>();
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
          Map<String, Object> emptyTags = new HashMap<>();
          LLMObs.SubmitEvaluation(NoOpLLMObsSpan.INSTANCE, "label", 0.5, emptyTags);
          LLMObs.SubmitEvaluation(NoOpLLMObsSpan.INSTANCE, "label", 0.5, "app", emptyTags);
          LLMObs.SubmitEvaluation(NoOpLLMObsSpan.INSTANCE, "label", "value", emptyTags);
          LLMObs.SubmitEvaluation(NoOpLLMObsSpan.INSTANCE, "label", "value", "app", emptyTags);
        });
  }

  @Test
  void testEvaluationSubmissionWithVariousScoreValues() {
    LLMObsSpan span = NoOpLLMObsSpan.INSTANCE;
    Map<String, Object> tags = new HashMap<>();
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
    Map<String, Object> tags = new HashMap<>();
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
    Map<String, Object> emptyTags = new HashMap<>();

    assertDoesNotThrow(
        () -> {
          LLMObs.SubmitEvaluation(span, "score", 0.75, emptyTags);
          LLMObs.SubmitEvaluation(span, "category", "good", emptyTags);
        });
  }

  @Test
  void testSpanCreationWithCustomFactoryReturnsActualSpans() throws Exception {
    LLMObs.LLMObsSpanFactory mockFactory = mock(LLMObs.LLMObsSpanFactory.class);
    LLMObs.LLMObsEvalProcessor mockEvalProcessor = mock(LLMObs.LLMObsEvalProcessor.class);
    LLMObsSpan mockLLMSpan = mock(LLMObsSpan.class);
    LLMObsSpan mockAgentSpan = mock(LLMObsSpan.class);
    LLMObsSpan mockToolSpan = mock(LLMObsSpan.class);
    LLMObsSpan mockTaskSpan = mock(LLMObsSpan.class);
    LLMObsSpan mockWorkflowSpan = mock(LLMObsSpan.class);
    LLMObsSpan mockEmbeddingSpan = mock(LLMObsSpan.class);
    LLMObsSpan mockRetrievalSpan = mock(LLMObsSpan.class);

    when(mockFactory.startLLMSpan("chat-completion", "gpt-4", "openai", "my-app", "session-1"))
        .thenReturn(mockLLMSpan);
    when(mockFactory.startAgentSpan("agent-task", "my-app", "session-1")).thenReturn(mockAgentSpan);
    when(mockFactory.startToolSpan("weather-tool", "my-app", "session-1")).thenReturn(mockToolSpan);
    when(mockFactory.startTaskSpan("summarize-task", "my-app", "session-1"))
        .thenReturn(mockTaskSpan);
    when(mockFactory.startWorkflowSpan("data-workflow", "my-app", "session-1"))
        .thenReturn(mockWorkflowSpan);
    when(mockFactory.startEmbeddingSpan(
            "text-embed", "my-app", "openai", "text-embedding-ada-002", "session-1"))
        .thenReturn(mockEmbeddingSpan);
    when(mockFactory.startRetrievalSpan("document-retrieval", "my-app", "session-1"))
        .thenReturn(mockRetrievalSpan);

    setStaticField("SPAN_FACTORY", mockFactory);
    setStaticField("EVAL_PROCESSOR", mockEvalProcessor);

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

    Map<String, Object> scoreTags = new HashMap<>();
    scoreTags.put("test", "value");
    LLMObs.SubmitEvaluation(llmSpan, "accuracy", 0.95, scoreTags);

    Map<String, Object> categoricalTags = new HashMap<>();
    categoricalTags.put("reviewer", "human");
    LLMObs.SubmitEvaluation(agentSpan, "quality", "excellent", "eval-app", categoricalTags);

    assertSame(mockLLMSpan, llmSpan);
    assertSame(mockAgentSpan, agentSpan);
    assertSame(mockToolSpan, toolSpan);
    assertSame(mockTaskSpan, taskSpan);
    assertSame(mockWorkflowSpan, workflowSpan);
    assertSame(mockEmbeddingSpan, embeddingSpan);
    assertSame(mockRetrievalSpan, retrievalSpan);

    assertNotSame(NoOpLLMObsSpan.INSTANCE, llmSpan);
    assertNotSame(NoOpLLMObsSpan.INSTANCE, agentSpan);
    assertNotSame(NoOpLLMObsSpan.INSTANCE, toolSpan);
    assertNotSame(NoOpLLMObsSpan.INSTANCE, taskSpan);
    assertNotSame(NoOpLLMObsSpan.INSTANCE, workflowSpan);
    assertNotSame(NoOpLLMObsSpan.INSTANCE, embeddingSpan);
    assertNotSame(NoOpLLMObsSpan.INSTANCE, retrievalSpan);

    verify(mockEvalProcessor).SubmitEvaluation(mockLLMSpan, "accuracy", 0.95, scoreTags);
    verify(mockEvalProcessor)
        .SubmitEvaluation(mockAgentSpan, "quality", "excellent", "eval-app", categoricalTags);
  }

  @Test
  void testSpanCreationWithNullParametersUsingCustomFactory() throws Exception {
    LLMObs.LLMObsSpanFactory mockFactory = mock(LLMObs.LLMObsSpanFactory.class);
    LLMObsSpan mockSpan = mock(LLMObsSpan.class);

    when(mockFactory.startLLMSpan("test-span", "gpt-4", "openai", null, null)).thenReturn(mockSpan);
    when(mockFactory.startEmbeddingSpan("embed-span", null, null, null, null)).thenReturn(mockSpan);

    setStaticField("SPAN_FACTORY", mockFactory);

    LLMObsSpan llmSpan = LLMObs.startLLMSpan("test-span", "gpt-4", "openai", null, null);
    LLMObsSpan embeddingSpan = LLMObs.startEmbeddingSpan("embed-span", null, null, null, null);

    assertSame(mockSpan, llmSpan);
    assertSame(mockSpan, embeddingSpan);

    verify(mockFactory).startLLMSpan("test-span", "gpt-4", "openai", null, null);
    verify(mockFactory).startEmbeddingSpan("embed-span", null, null, null, null);
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
}
