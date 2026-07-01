package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsSpan;
import datadog.trace.api.llmobs.LLMObsTags;
import datadog.trace.api.llmobs.noop.NoOpLLMObsEvalProcessor;
import datadog.trace.api.llmobs.noop.NoOpLLMObsSpanFactory;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import datadog.trace.junit.utils.config.WithConfig;
import datadog.trace.llmobs.domain.DDLLMObsSpan;
import datadog.trace.llmobs.domain.LLMObsInternal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@WithConfig(key = "llmobs.enabled", value = "true")
class LLMObsCompletableFuturePropagationTest extends AbstractInstrumentationTest {
  private static final String LLMOBS_TAG_PREFIX = "_ml_obs_tag.";
  private static final String SESSION_ID = "session-from-workflow";

  @BeforeAll
  static void setupLlmObsApi() {
    LLMObsInternal.setLLMObsSpanFactory(new TestLLMObsSpanFactory());
    LLMObsInternal.setLLMObsEvalProcessor(NoOpLLMObsEvalProcessor.INSTANCE);
  }

  @AfterAll
  static void resetLlmObsApi() {
    LLMObsInternal.setLLMObsSpanFactory(NoOpLLMObsSpanFactory.INSTANCE);
    LLMObsInternal.setLLMObsEvalProcessor(NoOpLLMObsEvalProcessor.INSTANCE);
  }

  @Test
  void activeLlmObsContextPropagatesThroughCompletableFutureExecutor() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    AgentSpan request = startSpan("test", "request");
    LLMObsSpan workflow = null;
    TaskSpanResult task = null;

    try (AgentScope scope = activateSpan(request)) {
      workflow = LLMObs.startWorkflowSpan("workflow", null, SESSION_ID);
      String workflowTraceId = workflow.getTraceId().toString();

      CompletableFuture<TaskSpanResult> future =
          CompletableFuture.supplyAsync(
              () -> {
                LLMObsSpan child = LLMObs.startTaskSpan("task", null, null);
                try {
                  assertEquals(workflowTraceId, child.getTraceId().toString());
                  return new TaskSpanResult(child.getTraceId().toString(), child.getSpanId());
                } finally {
                  child.finish();
                }
              },
              executor);

      task = future.get(5, TimeUnit.SECONDS);
    } finally {
      if (workflow != null) {
        workflow.finish();
      }
      request.finish();
      executor.shutdownNow();
    }

    writer.waitForTraces(1);
    List<DDSpan> trace = writer.get(0);

    DDSpan requestSpan = findSpan(trace, "request");
    DDSpan workflowSpan = findSpan(trace, "workflow");
    DDSpan taskSpan = findSpan(trace, "task");

    assertEquals(0L, requestSpan.getParentId());
    assertEquals(task.traceId, taskSpan.getTraceId().toString());
    assertEquals(task.spanId, taskSpan.getSpanId());
    assertEquals(requestSpan.getTraceId(), workflowSpan.getTraceId());
    assertEquals(requestSpan.getTraceId(), taskSpan.getTraceId());
    assertNotEquals(0L, workflowSpan.getSpanId());
    assertEquals(DDSpanTypes.LLMOBS, workflowSpan.getType());
    assertEquals(DDSpanTypes.LLMOBS, taskSpan.getType());
    assertEquals(String.valueOf(workflowSpan.getSpanId()), taskSpan.getTag(parentIdTag()));
    assertEquals(SESSION_ID, workflowSpan.getTag(sessionIdTag()));
    assertEquals(SESSION_ID, taskSpan.getTag(sessionIdTag()));
  }

  private static DDSpan findSpan(List<DDSpan> trace, String operationName) {
    return trace.stream()
        .filter(span -> operationName.contentEquals(span.getOperationName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing span: " + operationName));
  }

  private static String parentIdTag() {
    return LLMOBS_TAG_PREFIX + "parent_id";
  }

  private static String sessionIdTag() {
    return LLMOBS_TAG_PREFIX + LLMObsTags.SESSION_ID;
  }

  private static final class TaskSpanResult {
    private final String traceId;
    private final long spanId;

    private TaskSpanResult(String traceId, long spanId) {
      this.traceId = traceId;
      this.spanId = spanId;
    }
  }

  private static final class TestLLMObsSpanFactory implements LLMObs.LLMObsSpanFactory {
    private static final String ML_APP = "test-ml-app";
    private static final String SERVICE_NAME = "test-service";
    private static final WellKnownTags WELL_KNOWN_TAGS =
        new WellKnownTags("runtime-id", "host", "env", SERVICE_NAME, "version", "java");

    @Override
    public LLMObsSpan startLLMSpan(
        String spanName, String modelName, String modelProvider, String mlApp, String sessionId) {
      DDLLMObsSpan span = start(Tags.LLMOBS_LLM_SPAN_KIND, spanName, mlApp, sessionId);
      span.setTag(LLMObsTags.MODEL_NAME, modelName);
      span.setTag(LLMObsTags.MODEL_PROVIDER, modelProvider);
      return span;
    }

    @Override
    public LLMObsSpan startAgentSpan(String spanName, String mlApp, String sessionId) {
      return start(Tags.LLMOBS_AGENT_SPAN_KIND, spanName, mlApp, sessionId);
    }

    @Override
    public LLMObsSpan startToolSpan(String spanName, String mlApp, String sessionId) {
      return start(Tags.LLMOBS_TOOL_SPAN_KIND, spanName, mlApp, sessionId);
    }

    @Override
    public LLMObsSpan startTaskSpan(String spanName, String mlApp, String sessionId) {
      return start(Tags.LLMOBS_TASK_SPAN_KIND, spanName, mlApp, sessionId);
    }

    @Override
    public LLMObsSpan startWorkflowSpan(String spanName, String mlApp, String sessionId) {
      return start(Tags.LLMOBS_WORKFLOW_SPAN_KIND, spanName, mlApp, sessionId);
    }

    @Override
    public LLMObsSpan startEmbeddingSpan(
        String spanName, String mlApp, String modelProvider, String modelName, String sessionId) {
      DDLLMObsSpan span = start(Tags.LLMOBS_EMBEDDING_SPAN_KIND, spanName, mlApp, sessionId);
      span.setTag(LLMObsTags.MODEL_NAME, modelName);
      span.setTag(LLMObsTags.MODEL_PROVIDER, modelProvider);
      return span;
    }

    @Override
    public LLMObsSpan startRetrievalSpan(String spanName, String mlApp, String sessionId) {
      return start(Tags.LLMOBS_RETRIEVAL_SPAN_KIND, spanName, mlApp, sessionId);
    }

    private static DDLLMObsSpan start(
        String kind, String spanName, String mlApp, String sessionId) {
      return new DDLLMObsSpan(
          kind, spanName, mlApp == null ? ML_APP : mlApp, sessionId, SERVICE_NAME, WELL_KNOWN_TAGS);
    }
  }
}
