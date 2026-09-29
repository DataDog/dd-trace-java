import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;
import static datadog.trace.test.junit.utils.assertions.Matchers.matches;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.microsoft.azure.functions.TraceContext;
import com.microsoft.azure.functions.internal.spi.middleware.MiddlewareChain;
import com.microsoft.azure.functions.internal.spi.middleware.MiddlewareContext;
import com.microsoft.azure.functions.worker.chain.FunctionExecutionMiddleware;
import com.microsoft.durabletask.azurefunctions.internal.middleware.OrchestrationMiddleware;
import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.ExecutionStartedEvent;
import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.HistoryEvent;
import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.OrchestrationInstance;
import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.OrchestratorRequest;
import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.SubOrchestrationInstanceFailedEvent;
import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.TaskCompletedEvent;
import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.TaskFailedEvent;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import datadog.trace.instrumentation.azure.functions.worker.DurableFunctionsUtils;
import datadog.trace.instrumentation.azure.functions.worker.DurableOrchestrationUtils;
import java.lang.reflect.Constructor;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

abstract class AzureFunctionsWorkerTest extends AbstractInstrumentationTest {

  abstract String operation();

  @TableTest({
    "scenario      | annotation                  | trigger             ",
    "orchestration | DurableOrchestrationTrigger | DurableOrchestration",
    "activity      | DurableActivityTrigger      | DurableActivity     ",
    "entity        | DurableEntityTrigger        | DurableEntity       "
  })
  void createsSpanForDurableTrigger(String annotation, String trigger) throws Exception {
    MiddlewareContext context = contextFor(annotation, "MyFunction");

    if ("DurableOrchestration".equals(trigger)) {
      invokeOrchestration(
          context,
          Collections.emptyList(),
          Collections.singletonList(executionStarted()),
          mock(MiddlewareChain.class));
    } else {
      new FunctionExecutionMiddleware().invoke(context, mock(MiddlewareChain.class));
    }

    assertTraces(trace(durableSpan("MyFunction", trigger)));
  }

  @Test
  void doesNotCreateSpanForNonDurableFunction() throws Exception {
    MiddlewareContext context = contextFor(null, "HttpFunction");

    new FunctionExecutionMiddleware().invoke(context, mock(MiddlewareChain.class));

    assertTraces();
  }

  @Test
  void doesNotCreateSpanForSuccessfulOrchestrationReplay() throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");

    invokeOrchestration(
        context,
        Collections.singletonList(executionStarted()),
        Collections.singletonList(taskCompleted()),
        mock(MiddlewareChain.class));

    assertTraces();
  }

  @Test
  @SuppressWarnings("unchecked")
  void doesNotTraversePastEventsForReplayDecision() {
    List<HistoryEvent> pastEvents = mock(List.class);
    when(pastEvents.isEmpty()).thenReturn(false);

    assertFalse(
        DurableOrchestrationUtils.shouldTrace(
            pastEvents, Collections.singletonList(taskCompleted())));

    verify(pastEvents).isEmpty();
    verifyNoMoreInteractions(pastEvents);
  }

  @Test
  void failsOpenForUnexpectedParsedEvent() {
    assertTrue(
        DurableOrchestrationUtils.shouldTrace(
            Collections.singletonList(executionStarted()),
            Collections.singletonList(new Object())));
  }

  @Test
  void createsSpanForInitialOrchestrationExecution() throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");

    invokeOrchestration(
        context,
        Collections.emptyList(),
        Collections.singletonList(executionStarted()),
        mock(MiddlewareChain.class));

    assertTraces(trace(durableSpan("Orchestrator", "DurableOrchestration")));
  }

  @TableTest({
    "scenario                 | subOrchestration",
    "failed activity          | false           ",
    "failed sub-orchestration | true            "
  })
  void createsSpanForOrchestrationReplayWithFailure(boolean subOrchestration) throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");

    invokeOrchestration(
        context,
        Collections.singletonList(executionStarted()),
        Collections.singletonList(failureEvent(subOrchestration)),
        mock(MiddlewareChain.class));

    assertTraces(trace(durableSpan("Orchestrator", "DurableOrchestration")));
  }

  @Test
  void marksRetainedOrchestrationFailureAsError() throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    MiddlewareChain chain = mock(MiddlewareChain.class);
    doAnswer(
            invocation -> {
              throw new IllegalStateException("activity failure");
            })
        .when(chain)
        .doNext(context);

    invokeOrchestration(
        context,
        Collections.singletonList(executionStarted()),
        Collections.singletonList(failureEvent(false)),
        chain);

    assertTraces(
        trace(
            span()
                .root()
                .operationName(Pattern.compile(Pattern.quote(operation())))
                .resourceName("DurableOrchestration Orchestrator")
                .type(DDSpanTypes.SERVERLESS)
                .error(true)
                .tags(
                    defaultTags(),
                    error(IllegalStateException.class, "activity failure"),
                    tag(Tags.COMPONENT, matches(Pattern.quote("azure-functions"))),
                    tag(Tags.SPAN_KIND, is(Tags.SPAN_KIND_SERVER)),
                    tag("aas.function.name", is("Orchestrator")),
                    tag("aas.function.trigger", is("DurableOrchestration")))));
  }

  @Test
  void createsErrorSpanWhenSuppressedOrchestrationReplayFails() throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    MiddlewareChain chain = mock(MiddlewareChain.class);
    AtomicLong invocationStartMillis = new AtomicLong();
    doAnswer(
            invocation -> {
              invocationStartMillis.set(System.currentTimeMillis());
              Thread.sleep(25);
              throw new IllegalStateException("replay failure");
            })
        .when(chain)
        .doNext(context);

    invokeOrchestration(
        context,
        Collections.singletonList(executionStarted()),
        Collections.singletonList(taskCompleted()),
        chain);

    writer.waitForTraces(1);
    DDSpan errorSpan = writer.firstTrace().get(0);
    assertTrue(errorSpan.getStartTime() <= MILLISECONDS.toNanos(invocationStartMillis.get()));
    assertTrue(errorSpan.getDurationNano() >= MILLISECONDS.toNanos(20));

    assertTraces(
        trace(
            span()
                .root()
                .operationName(Pattern.compile(Pattern.quote(operation())))
                .resourceName("DurableOrchestration Orchestrator")
                .type(DDSpanTypes.SERVERLESS)
                .error(true)
                .tags(
                    defaultTags(),
                    error(IllegalStateException.class, "replay failure"),
                    tag(Tags.COMPONENT, matches(Pattern.quote("azure-functions"))),
                    tag(Tags.SPAN_KIND, is(Tags.SPAN_KIND_SERVER)),
                    tag("aas.function.name", is("Orchestrator")),
                    tag("aas.function.trigger", is("DurableOrchestration")))));
  }

  @Test
  void stopsTraversingRepeatedExceptionCause() {
    AtomicInteger causeReads = new AtomicInteger();
    Throwable[] cycle = new Throwable[2];
    cycle[0] =
        new RuntimeException("first") {
          @Override
          public Throwable getCause() {
            if (causeReads.incrementAndGet() > 2) {
              throw new AssertionError("cause traversal did not terminate");
            }
            return cycle[1];
          }
        };
    cycle[1] =
        new RuntimeException("second") {
          @Override
          public Throwable getCause() {
            if (causeReads.incrementAndGet() > 2) {
              throw new AssertionError("cause traversal did not terminate");
            }
            return cycle[0];
          }
        };

    assertFalse(DurableFunctionsUtils.isReplayControlFlow(cycle[0]));
    assertEquals(2, causeReads.get());
  }

  @Test
  void honorsW3cDropDecisionWithoutTracestate() throws Exception {
    MiddlewareContext context = contextFor("DurableActivityTrigger", "Activity");
    TraceContext traceContext = mock(TraceContext.class);
    when(traceContext.getTraceparent())
        .thenReturn("00-0000000000000000000000000000002a-000000000000002b-00");
    when(traceContext.getTracestate()).thenReturn(null);
    when(context.getTraceContext()).thenReturn(traceContext);
    AtomicInteger priority = new AtomicInteger(Integer.MIN_VALUE);
    MiddlewareChain chain = mock(MiddlewareChain.class);
    doAnswer(
            invocation -> {
              priority.set(AgentTracer.activeSpan().spanContext().getSamplingPriority());
              return null;
            })
        .when(chain)
        .doNext(context);

    new FunctionExecutionMiddleware().invoke(context, chain);

    writer.waitForTraces(1);
    DDSpan span = writer.firstTrace().get(0);
    assertEquals("42", span.getTraceId().toString());
    assertEquals(43L, span.getParentId());
    assertEquals(SAMPLER_DROP, priority.get());
    assertEquals(SAMPLER_DROP, span.samplingPriority());
  }

  @Test
  void honorsW3cKeepDecisionWithoutTracestate() throws Exception {
    MiddlewareContext context = contextFor("DurableActivityTrigger", "Activity");
    TraceContext traceContext = mock(TraceContext.class);
    when(traceContext.getTraceparent())
        .thenReturn("00-0000000000000000000000000000002a-000000000000002b-01");
    when(traceContext.getTracestate()).thenReturn(null);
    when(context.getTraceContext()).thenReturn(traceContext);
    AtomicInteger priority = new AtomicInteger(Integer.MIN_VALUE);
    MiddlewareChain chain = mock(MiddlewareChain.class);
    doAnswer(
            invocation -> {
              priority.set(AgentTracer.activeSpan().spanContext().getSamplingPriority());
              return null;
            })
        .when(chain)
        .doNext(context);

    new FunctionExecutionMiddleware().invoke(context, chain);

    writer.waitForTraces(1);
    DDSpan span = writer.firstTrace().get(0);
    assertEquals("42", span.getTraceId().toString());
    assertEquals(43L, span.getParentId());
    assertEquals(SAMPLER_KEEP, priority.get());
    assertEquals(SAMPLER_KEEP, span.samplingPriority());
  }

  @Test
  void honorsDatadogDropDecisionWhenAzureClearsW3cSampledFlag() throws Exception {
    MiddlewareContext context = contextFor("DurableActivityTrigger", "Activity");
    TraceContext traceContext = mock(TraceContext.class);
    when(traceContext.getTraceparent())
        .thenReturn("00-0000000000000000000000000000002a-000000000000002b-00");
    when(traceContext.getTracestate()).thenReturn("dd=s:0");
    when(context.getTraceContext()).thenReturn(traceContext);
    AtomicInteger priority = new AtomicInteger(Integer.MIN_VALUE);
    MiddlewareChain chain = mock(MiddlewareChain.class);
    doAnswer(
            invocation -> {
              priority.set(AgentTracer.activeSpan().spanContext().getSamplingPriority());
              return null;
            })
        .when(chain)
        .doNext(context);

    new FunctionExecutionMiddleware().invoke(context, chain);

    assertEquals(SAMPLER_DROP, priority.get());
    writer.waitForTraces(1);
    DDSpan span = writer.firstTrace().get(0);
    assertEquals("42", span.getTraceId().toString());
    assertEquals(43L, span.getParentId());
    assertEquals(SAMPLER_DROP, span.samplingPriority());
  }

  @Test
  void honorsOtelDropDecisionWhenAzureClearsW3cSampledFlag() throws Exception {
    MiddlewareContext context = contextFor("DurableActivityTrigger", "Activity");
    TraceContext traceContext = mock(TraceContext.class);
    when(traceContext.getTraceparent())
        .thenReturn("00-0000000000000000000000000000002a-000000000000002b-00");
    when(traceContext.getTracestate()).thenReturn("ot=rv:00000000000000;th:8,vendor=value");
    when(context.getTraceContext()).thenReturn(traceContext);
    AtomicInteger priority = new AtomicInteger(Integer.MIN_VALUE);
    MiddlewareChain chain = mock(MiddlewareChain.class);
    doAnswer(
            invocation -> {
              priority.set(AgentTracer.activeSpan().spanContext().getSamplingPriority());
              return null;
            })
        .when(chain)
        .doNext(context);

    new FunctionExecutionMiddleware().invoke(context, chain);

    assertEquals(SAMPLER_DROP, priority.get());
    writer.waitForTraces(1);
    DDSpan span = writer.firstTrace().get(0);
    assertEquals("42", span.getTraceId().toString());
    assertEquals(43L, span.getParentId());
    assertEquals(SAMPLER_DROP, span.samplingPriority());
  }

  @Test
  void doesNotForceKeepForMalformedDatadogSamplingPriority() throws Exception {
    MiddlewareContext context = contextFor("DurableActivityTrigger", "Activity");
    TraceContext traceContext = mock(TraceContext.class);
    when(traceContext.getTraceparent())
        .thenReturn("00-0000000000000000000000000000002a-000000000000002b-00");
    when(traceContext.getTracestate()).thenReturn("dd=s:not-a-number");
    when(context.getTraceContext()).thenReturn(traceContext);
    AtomicInteger priority = new AtomicInteger(Integer.MIN_VALUE);
    MiddlewareChain chain = mock(MiddlewareChain.class);
    doAnswer(
            invocation -> {
              priority.set(AgentTracer.activeSpan().spanContext().getSamplingPriority());
              return null;
            })
        .when(chain)
        .doNext(context);

    new FunctionExecutionMiddleware().invoke(context, chain);

    assertEquals(SAMPLER_DROP, priority.get());
  }

  @Test
  void honorsW3cDropWhenDatadogTracestateConflicts() throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    TraceContext traceContext = mock(TraceContext.class);
    when(traceContext.getTraceparent())
        .thenReturn("00-0000000000000000000000000000002a-000000000000002b-00");
    when(traceContext.getTracestate()).thenReturn("vendor=value,\tdd=s:2;o:rum\t");
    when(context.getTraceContext()).thenReturn(traceContext);
    AtomicInteger priority = new AtomicInteger(Integer.MIN_VALUE);
    MiddlewareChain chain = mock(MiddlewareChain.class);
    doAnswer(
            invocation -> {
              priority.set(AgentTracer.activeSpan().spanContext().getSamplingPriority());
              return null;
            })
        .when(chain)
        .doNext(context);

    invokeOrchestration(
        context, Collections.emptyList(), Collections.singletonList(executionStarted()), chain);

    writer.waitForTraces(1);
    DDSpan span = writer.firstTrace().get(0);
    assertEquals("42", span.getTraceId().toString());
    assertEquals(43L, span.getParentId());
    assertEquals(SAMPLER_DROP, priority.get());
  }

  @TableTest({
    "scenario                            | controlFlowTypeName                                                ",
    "legacy OrchestratorBlockedException | com.microsoft.durabletask.OrchestratorBlockedException             ",
    "OrchestratorBlockedException        | com.microsoft.durabletask.interruption.OrchestratorBlockedException",
    "ContinueAsNewInterruption           | com.microsoft.durabletask.interruption.ContinueAsNewInterruption   "
  })
  @SuppressWarnings("unchecked")
  void doesNotMarkReplayControlFlowAsError(String controlFlowTypeName) throws Exception {
    Class<? extends Throwable> controlFlowType;
    try {
      controlFlowType = (Class<? extends Throwable>) Class.forName(controlFlowTypeName);
    } catch (ClassNotFoundException ignored) {
      return;
    }
    Throwable controlFlow = instantiateThrowable(controlFlowType);
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    MiddlewareChain chain = mock(MiddlewareChain.class);
    doAnswer(
            invocation -> {
              throw new RuntimeException(controlFlow);
            })
        .when(chain)
        .doNext(context);

    invokeOrchestration(
        context, Collections.emptyList(), Collections.singletonList(executionStarted()), chain);

    assertTraces(trace(durableSpan("Orchestrator", "DurableOrchestration")));
  }

  @Test
  void marksApplicationFailuresAsErrors() throws Exception {
    MiddlewareContext context = contextFor("DurableActivityTrigger", "Activity");
    MiddlewareChain chain = mock(MiddlewareChain.class);
    doAnswer(
            invocation -> {
              throw new IllegalStateException("failure");
            })
        .when(chain)
        .doNext(context);

    assertThrows(
        IllegalStateException.class,
        () -> new FunctionExecutionMiddleware().invoke(context, chain));

    assertTraces(
        trace(
            span()
                .root()
                .operationName(Pattern.compile(Pattern.quote(operation())))
                .resourceName("DurableActivity Activity")
                .type(DDSpanTypes.SERVERLESS)
                .error(true)
                .tags(
                    defaultTags(),
                    error(IllegalStateException.class, "failure"),
                    tag(Tags.COMPONENT, matches(Pattern.quote("azure-functions"))),
                    tag(Tags.SPAN_KIND, is(Tags.SPAN_KIND_SERVER)),
                    tag("aas.function.name", is("Activity")),
                    tag("aas.function.trigger", is("DurableActivity")))));
  }

  private SpanMatcher durableSpan(String functionName, String trigger) {
    return span()
        .root()
        .operationName(Pattern.compile(Pattern.quote(operation())))
        .resourceName(trigger + " " + functionName)
        .type(DDSpanTypes.SERVERLESS)
        .error(false)
        .tags(
            defaultTags(),
            tag(Tags.COMPONENT, matches(Pattern.quote("azure-functions"))),
            tag(Tags.SPAN_KIND, is(Tags.SPAN_KIND_SERVER)),
            tag("aas.function.name", is(functionName)),
            tag("aas.function.trigger", is(trigger)));
  }

  private static void invokeOrchestration(
      MiddlewareContext context,
      List<HistoryEvent> pastEvents,
      List<HistoryEvent> newEvents,
      MiddlewareChain functionChain)
      throws Exception {
    OrchestratorRequest request =
        OrchestratorRequest.newBuilder()
            .setInstanceId("test-instance")
            .addAllPastEvents(pastEvents)
            .addAllNewEvents(newEvents)
            .build();
    when(context.getParameterValue("input"))
        .thenReturn(Base64.getEncoder().encodeToString(request.toByteArray()));

    MiddlewareChain orchestrationChain = mock(MiddlewareChain.class);
    doAnswer(
            invocation -> {
              new FunctionExecutionMiddleware().invoke(context, functionChain);
              return null;
            })
        .when(orchestrationChain)
        .doNext(context);

    new OrchestrationMiddleware().invoke(context, orchestrationChain);
  }

  private static HistoryEvent executionStarted() {
    return HistoryEvent.newBuilder()
        .setExecutionStarted(
            ExecutionStartedEvent.newBuilder()
                .setName("Orchestrator")
                .setOrchestrationInstance(
                    OrchestrationInstance.newBuilder().setInstanceId("test-instance")))
        .build();
  }

  private static HistoryEvent taskCompleted() {
    return HistoryEvent.newBuilder()
        .setTaskCompleted(TaskCompletedEvent.getDefaultInstance())
        .build();
  }

  private static HistoryEvent failureEvent(boolean subOrchestration) {
    HistoryEvent.Builder event = HistoryEvent.newBuilder();
    if (subOrchestration) {
      event.setSubOrchestrationInstanceFailed(
          SubOrchestrationInstanceFailedEvent.getDefaultInstance());
    } else {
      event.setTaskFailed(TaskFailedEvent.getDefaultInstance());
    }
    return event.build();
  }

  private static Throwable instantiateThrowable(Class<? extends Throwable> type) throws Exception {
    Constructor<?> constructor = type.getDeclaredConstructors()[0];
    for (Constructor<?> candidate : type.getDeclaredConstructors()) {
      if (candidate.getParameterCount() < constructor.getParameterCount()) {
        constructor = candidate;
      }
    }
    constructor.setAccessible(true);
    Object[] arguments = new Object[constructor.getParameterCount()];
    Class<?>[] parameterTypes = constructor.getParameterTypes();
    for (int index = 0; index < parameterTypes.length; index++) {
      if (parameterTypes[index] == String.class) {
        arguments[index] = "test";
      } else if (parameterTypes[index] == boolean.class) {
        arguments[index] = false;
      } else if (parameterTypes[index] == char.class) {
        arguments[index] = '\0';
      } else if (parameterTypes[index].isPrimitive()) {
        arguments[index] = 0;
      }
    }
    return (Throwable) constructor.newInstance(arguments);
  }

  private static MiddlewareContext contextFor(String annotation, String functionName) {
    MiddlewareContext context = mock(MiddlewareContext.class);
    when(context.getFunctionName()).thenReturn(functionName);
    when(context.getParameterName(anyString()))
        .thenAnswer(
            invocation -> Objects.equals(invocation.getArgument(0), annotation) ? "input" : null);
    return context;
  }
}
