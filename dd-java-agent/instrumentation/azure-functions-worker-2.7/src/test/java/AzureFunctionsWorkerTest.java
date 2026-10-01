import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;
import static datadog.trace.test.junit.utils.assertions.Matchers.matches;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.microsoft.azure.functions.TraceContext;
import com.microsoft.azure.functions.internal.spi.middleware.MiddlewareChain;
import com.microsoft.azure.functions.internal.spi.middleware.MiddlewareContext;
import com.microsoft.azure.functions.worker.chain.FunctionExecutionMiddleware;
import com.microsoft.durabletask.OrchestrationRunner;
import com.microsoft.durabletask.azurefunctions.internal.middleware.OrchestrationMiddleware;
import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.ExecutionStartedEvent;
import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.HistoryEvent;
import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.OrchestrationInstance;
import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.OrchestratorRequest;
import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.SubOrchestrationInstanceFailedEvent;
import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.TaskCompletedEvent;
import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.TaskFailedEvent;
import datadog.appsec.api.blocking.BlockingException;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.azure.DurableOrchestrationState;
import datadog.trace.core.DDSpan;
import datadog.trace.instrumentation.azure.functions.worker.AzureFunctionsWorkerInstrumentation;
import datadog.trace.instrumentation.azure.functions.worker.DurableFunctionsUtils;
import datadog.trace.instrumentation.azure.functions.worker.DurableOrchestrationExecutorInstrumentation;
import datadog.trace.instrumentation.azure.functions.worker.DurableOrchestrationUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
          context, emptyList(), singletonList(executionStarted()), mock(MiddlewareChain.class));
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
        singletonList(executionStarted()),
        singletonList(taskCompleted()),
        mock(MiddlewareChain.class));

    assertTraces();
  }

  @Test
  void preservesUserSpansFromNewWorkDuringOrchestrationReplay() throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    MiddlewareChain chain = mock(MiddlewareChain.class);
    doAnswer(
            invocation -> {
              AgentSpan userSpan = startSpan("test", "user-code");
              userSpan.finish();
              return null;
            })
        .when(chain)
        .doNext(context);

    invokeOrchestration(
        context, singletonList(executionStarted()), singletonList(taskCompleted()), chain);

    writer.waitForTraces(1);
    assertEquals(1, writer.firstTrace().size());
    assertEquals("user-code", writer.firstTrace().get(0).getOperationName().toString());
  }

  @Test
  @SuppressWarnings("unchecked")
  void doesNotTraversePastEventsForReplayDecision() {
    List<HistoryEvent> pastEvents = mock(List.class);
    when(pastEvents.isEmpty()).thenReturn(false);

    assertFalse(DurableOrchestrationUtils.shouldTrace(pastEvents, singletonList(taskCompleted())));

    verify(pastEvents).isEmpty();
    verifyNoMoreInteractions(pastEvents);
  }

  @Test
  void failsOpenForUnexpectedParsedEvent() {
    assertTrue(
        DurableOrchestrationUtils.shouldTrace(
            singletonList(executionStarted()), singletonList(new Object())));
  }

  @Test
  void createsSpanForInitialOrchestrationExecution() throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");

    invokeOrchestration(
        context, emptyList(), singletonList(executionStarted()), mock(MiddlewareChain.class));

    assertTraces(trace(durableSpan("Orchestrator", "DurableOrchestration")));
  }

  @Test
  void orchestrationWithoutRemoteParentDoesNotJoinAmbientTrace() throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    AgentSpan unrelated = startSpan("test", "unrelated");
    try (ContextScope scope = activateSpan(unrelated)) {
      invokeOrchestration(
          context, emptyList(), singletonList(executionStarted()), mock(MiddlewareChain.class));
    } finally {
      unrelated.finish();
    }

    writer.waitForTraces(2);
    DDSpan orchestration =
        writer.stream()
            .flatMap(List::stream)
            .filter(span -> "DurableOrchestration".equals(span.getTag("aas.function.trigger")))
            .findFirst()
            .orElseThrow(AssertionError::new);
    assertEquals(0L, orchestration.getParentId());
    assertNotEquals(unrelated.getTraceId(), orchestration.getTraceId());
  }

  @Test
  void doesNotCreateAzureSpanForStandaloneOrchestration() {
    OrchestratorRequest request =
        OrchestratorRequest.newBuilder()
            .setInstanceId("standalone-instance")
            .addNewEvents(executionStarted())
            .build();

    OrchestrationRunner.loadAndRun(request.toByteArray(), context -> null);

    assertTraces();
  }

  @Test
  void closesOrchestrationContextWhenMiddlewareThrows() throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    when(context.getParameterValue("input")).thenReturn("invalid base64!");
    TraceContext traceContext = mock(TraceContext.class);
    when(traceContext.getTraceparent())
        .thenReturn("00-0000000000000000000000000000002a-000000000000002b-01");
    when(context.getTraceContext()).thenReturn(traceContext);
    AgentSpan previousSpan = activeSpan();

    assertThrows(
        IllegalArgumentException.class,
        () -> new OrchestrationMiddleware().invoke(context, mock(MiddlewareChain.class)));

    assertSame(previousSpan, activeSpan());
    assertNull(DurableOrchestrationState.current());
    writer.waitForTraces(1);
    assertEquals(1, writer.size());
    DDSpan errorSpan = writer.firstTrace().get(0);
    assertEquals("42", errorSpan.getTraceId().toString());
    assertEquals(43L, errorSpan.getParentId());
    assertEquals("DurableOrchestration Orchestrator", errorSpan.getResourceName().toString());
    assertTrue(errorSpan.isError());
  }

  @Test
  void retainsTraceParentForOrchestrationErrorWithoutMiddlewareState() throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    TraceContext traceContext = mock(TraceContext.class);
    when(traceContext.getTraceparent())
        .thenReturn("00-0000000000000000000000000000002a-000000000000002b-01");
    when(context.getTraceContext()).thenReturn(traceContext);
    MiddlewareChain chain = mock(MiddlewareChain.class);
    doAnswer(
            invocation -> {
              throw new IllegalStateException("worker failure");
            })
        .when(chain)
        .doNext(context);

    assertThrows(
        IllegalStateException.class,
        () -> new FunctionExecutionMiddleware().invoke(context, chain));

    writer.waitForTraces(1);
    assertEquals(1, writer.size());
    DDSpan errorSpan = writer.firstTrace().get(0);
    assertEquals("42", errorSpan.getTraceId().toString());
    assertEquals(43L, errorSpan.getParentId());
    assertEquals("DurableOrchestration Orchestrator", errorSpan.getResourceName().toString());
    assertTrue(errorSpan.isError());
  }

  @Test
  void closesOrchestrationScopeWhenFailureInspectionFailsOpen() throws Exception {
    Field actionsMethod = DurableOrchestrationUtils.class.getDeclaredField("getActionsMethod");
    actionsMethod.setAccessible(true);
    Object previousMethod = actionsMethod.get(null);
    AgentSpan previousSpan = activeSpan();
    try {
      try (ContextScope context = DurableOrchestrationState.activate(null, "Orchestrator")) {
        DurableOrchestrationState state = DurableOrchestrationState.current();
        ContextScope scope = DurableOrchestrationUtils.startSpanScope(state);
        state.setSpan(activeSpan());

        DurableOrchestrationExecutorInstrumentation.ExecuteAdvice.onExit(
            scope, state, null, 0, null, new NullActionsResult());

        assertNull(state.span());
        assertSame(previousSpan, activeSpan());
        DurableOrchestrationExecutorInstrumentation.ExecuteAdvice.onExit(
            null, state, null, DurableFunctionsUtils.nowMicros(), null, new NullActionsResult());
        assertFalse(state.errorRecorded());
      }
    } finally {
      actionsMethod.set(null, previousMethod);
    }
    assertNull(DurableOrchestrationState.current());
    writer.waitForTraces(1);
    assertEquals(1, writer.size());
  }

  @Test
  void finishesErrorSpanWhenDecorationThrows() throws Exception {
    AgentSpan span = mock(AgentSpan.class);
    when(span.addThrowable(any(Throwable.class), anyByte()))
        .thenThrow(new BlockingException("blocked"));
    when(span.isError()).thenReturn(false, true);
    try (ContextScope context = DurableOrchestrationState.activate(null, "Orchestrator")) {
      DurableOrchestrationState state = DurableOrchestrationState.current();

      assertThrows(
          BlockingException.class,
          () ->
              DurableFunctionsUtils.recordOrchestrationError(
                  span, new IllegalStateException("failure"), state));

      verify(span).setError(true);
      verify(span).finish(anyLong());
      assertTrue(state.errorRecorded());
    }
    assertTraces();
  }

  @Test
  void closesActivityScopeWhenDecorationThrows() throws Exception {
    AgentSpan previousSpan = activeSpan();
    AgentSpan span = mock(AgentSpan.class, CALLS_REAL_METHODS);
    when(span.addThrowable(any(Throwable.class), anyByte()))
        .thenThrow(new BlockingException("blocked"));
    ContextScope scope = Context.current().with(span).attach();

    assertThrows(
        BlockingException.class,
        () ->
            AzureFunctionsWorkerInstrumentation.InvokeAdvice.onExit(
                contextFor("DurableActivityTrigger", "Activity"),
                scope,
                "DurableActivity",
                null,
                0,
                new IllegalStateException("failure")));

    assertSame(previousSpan, activeSpan());
    verify(span).finish();
    assertTraces();
  }

  @Test
  void findsOrchestrationSpanWhenAnotherSpanIsActive() throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    OrchestratorRequest request =
        OrchestratorRequest.newBuilder()
            .setInstanceId("test-instance")
            .addNewEvents(executionStarted())
            .build();
    when(context.getParameterValue("input"))
        .thenReturn(Base64.getEncoder().encodeToString(request.toByteArray()));

    MiddlewareChain orchestrationChain = mock(MiddlewareChain.class);
    doAnswer(
            invocation -> {
              AgentSpan intervening = startSpan("test", "intervening");
              try (ContextScope scope = activateSpan(intervening)) {
                new FunctionExecutionMiddleware().invoke(context, mock(MiddlewareChain.class));
              } finally {
                intervening.finish();
              }
              return null;
            })
        .when(orchestrationChain)
        .doNext(context);

    new OrchestrationMiddleware().invoke(context, orchestrationChain);

    writer.waitForTraces(1);
    List<DDSpan> spans = writer.firstTrace();
    assertEquals(2, spans.size());
    DDSpan orchestrationSpan =
        spans.stream()
            .filter(span -> "DurableOrchestration".equals(span.getTag("aas.function.trigger")))
            .findFirst()
            .orElseThrow(AssertionError::new);
    assertEquals("Orchestrator", orchestrationSpan.getTag("aas.function.name"));
    assertEquals(
        "DurableOrchestration Orchestrator", orchestrationSpan.getResourceName().toString());
  }

  @Test
  void findsOrchestrationSpanAcrossWorkerClassLoader() throws Exception {
    AgentSpan expectedSpan = mock(AgentSpan.class);
    ClassLoader firstWorker = isolatedWorkerLoader();
    ClassLoader secondWorker = isolatedWorkerLoader();

    try (ContextScope scope = DurableOrchestrationState.activate(null, "Orchestrator")) {
      DurableOrchestrationState.current().setSpan(expectedSpan);
      Class<?> firstUtils = Class.forName(DurableFunctionsUtils.class.getName(), true, firstWorker);
      Class<?> secondUtils =
          Class.forName(DurableFunctionsUtils.class.getName(), true, secondWorker);
      assertNotSame(DurableFunctionsUtils.class, firstUtils);
      assertNotSame(firstUtils, secondUtils);
      for (Class<?> workerUtils : new Class<?>[] {firstUtils, secondUtils}) {
        assertSame(
            DurableOrchestrationState.class,
            Class.forName(
                DurableOrchestrationState.class.getName(), true, workerUtils.getClassLoader()));
        assertSame(expectedSpan, workerUtils.getMethod("onOrchestrationInvoke").invoke(null));
      }
    }
    assertTraces();
  }

  @Test
  void workerAdviceDoesNotNeedDurableTaskClasses() throws Exception {
    assertFalse(
        asList(new AzureFunctionsWorkerInstrumentation().helperClassNames())
            .contains(DurableOrchestrationUtils.class.getName()));
    ClassLoader workerLoader = isolatedWorkerLoader(true);
    assertThrows(
        ClassNotFoundException.class,
        () -> workerLoader.loadClass("com.microsoft.durabletask.TaskOrchestrationExecutor"));
    Class<?> workerAdvice =
        Class.forName(
            AzureFunctionsWorkerInstrumentation.InvokeAdvice.class.getName(), true, workerLoader);
    assertNotSame(AzureFunctionsWorkerInstrumentation.InvokeAdvice.class, workerAdvice);
    Method onExit =
        workerAdvice.getMethod(
            "onExit",
            MiddlewareContext.class,
            ContextScope.class,
            String.class,
            AgentSpan.class,
            long.class,
            Throwable.class);
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");

    onExit.invoke(
        null,
        context,
        null,
        "DurableOrchestration",
        null,
        DurableFunctionsUtils.nowMicros(),
        new IllegalStateException("worker failure"));

    writer.waitForTraces(1);
    assertEquals(1, writer.size());
    assertEquals(
        "DurableOrchestration Orchestrator",
        writer.firstTrace().get(0).getResourceName().toString());
    assertTrue(writer.firstTrace().get(0).isError());
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
        singletonList(executionStarted()),
        singletonList(failureEvent(subOrchestration)),
        mock(MiddlewareChain.class));

    assertTraces(trace(durableSpan("Orchestrator", "DurableOrchestration")));
  }

  @Test
  void createsSpanForOrchestrationReplayWithEntityFailure() throws Exception {
    assumeTrue(
        HistoryEvent.getDescriptor().findFieldByNumber(26) != null,
        "Entity operation events require durabletask-client 1.9 or later");
    HistoryEvent entityFailure = HistoryEvent.parseFrom(new byte[] {(byte) 0xd2, 0x01, 0x00});
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");

    invokeOrchestration(
        context,
        singletonList(executionStarted()),
        singletonList(entityFailure),
        mock(MiddlewareChain.class));

    assertTraces(trace(durableSpan("Orchestrator", "DurableOrchestration")));
  }

  @Test
  void marksRetainedOrchestrationFailureAsError() throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    MiddlewareChain chain = mock(MiddlewareChain.class);
    doAnswer(
            invocation -> {
              throw new InvocationTargetException(new IllegalStateException("activity failure"));
            })
        .when(chain)
        .doNext(context);

    invokeOrchestration(
        context, singletonList(executionStarted()), singletonList(failureEvent(false)), chain);

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

  @TableTest({
    "scenario          | replay",
    "initial execution | false ",
    "completing replay | true  "
  })
  void marksSdkResultFailureAsError(boolean replay) throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    when(context.getReturnValue()).thenReturn(new FailingOutput());

    invokeOrchestration(
        context,
        replay ? singletonList(executionStarted()) : emptyList(),
        singletonList(replay ? taskCompleted() : executionStarted()),
        mock(MiddlewareChain.class));

    writer.waitForTraces(1);
    assertEquals(1, writer.size());
    DDSpan span = writer.firstTrace().get(0);
    assertEquals("DurableOrchestration Orchestrator", span.getResourceName().toString());
    assertTrue(span.isError());
    assertNotNull(span.getTag("error.type"));
    assertTrue(String.valueOf(span.getTag("error.message")).contains("serialization failure"));
  }

  @Test
  void marksSdkResultFailureWithoutWorkerAdvice() throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    when(context.getReturnValue()).thenReturn(new FailingOutput());
    OrchestratorRequest request =
        OrchestratorRequest.newBuilder()
            .setInstanceId("test-instance")
            .addNewEvents(executionStarted())
            .build();
    when(context.getParameterValue("input"))
        .thenReturn(Base64.getEncoder().encodeToString(request.toByteArray()));

    new OrchestrationMiddleware().invoke(context, mock(MiddlewareChain.class));

    writer.waitForTraces(1);
    assertEquals(1, writer.size());
    DDSpan span = writer.firstTrace().get(0);
    assertEquals("DurableOrchestration Orchestrator", span.getResourceName().toString());
    assertTrue(span.isError());
    assertTrue(String.valueOf(span.getTag("error.message")).contains("serialization failure"));
  }

  @Test
  void recordsFailureAfterExecutorReturns() throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    OrchestratorRequest request =
        OrchestratorRequest.newBuilder()
            .setInstanceId("test-instance")
            .addNewEvents(executionStarted())
            .build();
    when(context.getParameterValue("input"))
        .thenReturn(Base64.getEncoder().encodeToString(request.toByteArray()));
    doAnswer(
            invocation -> {
              throw new IllegalStateException("response failure");
            })
        .when(context)
        .updateReturnValue(any());

    assertThrows(
        IllegalStateException.class,
        () -> new OrchestrationMiddleware().invoke(context, mock(MiddlewareChain.class)));

    writer.waitForTraces(2);
    assertEquals(2, writer.stream().flatMap(List::stream).count());
    List<DDSpan> errors =
        writer.stream().flatMap(List::stream).filter(DDSpan::isError).collect(Collectors.toList());
    assertEquals(1, errors.size());
    assertEquals("DurableOrchestration Orchestrator", errors.get(0).getResourceName().toString());
    assertEquals("response failure", errors.get(0).getTag("error.message"));
  }

  @Test
  void doesNotDuplicateErrorWhenResponseFailsAfterFailedExecution() throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    OrchestratorRequest request =
        OrchestratorRequest.newBuilder()
            .setInstanceId("test-instance")
            .addNewEvents(executionStarted())
            .build();
    when(context.getParameterValue("input"))
        .thenReturn(Base64.getEncoder().encodeToString(request.toByteArray()));
    when(context.getReturnValue()).thenReturn(new FailingOutput());
    doAnswer(
            invocation -> {
              throw new IllegalStateException("response failure");
            })
        .when(context)
        .updateReturnValue(any());

    assertThrows(
        IllegalStateException.class,
        () -> new OrchestrationMiddleware().invoke(context, mock(MiddlewareChain.class)));

    writer.waitForTraces(1);
    assertEquals(1, writer.stream().flatMap(List::stream).count());
    DDSpan errorSpan = writer.firstTrace().get(0);
    assertTrue(errorSpan.isError());
    assertTrue(String.valueOf(errorSpan.getTag("error.message")).contains("serialization failure"));
  }

  @Test
  void createsErrorSpanWhenSuppressedOrchestrationReplayFails() throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    TraceContext traceContext = mock(TraceContext.class);
    when(traceContext.getTraceparent())
        .thenReturn(
            "00-0000000000000000000000000000002a-000000000000002b-01",
            "00-00000000000000000000000000000063-0000000000000064-01");
    when(context.getTraceContext()).thenReturn(traceContext);
    MiddlewareChain chain = mock(MiddlewareChain.class);
    AtomicLong invocationStartMicros = new AtomicLong();
    doAnswer(
            invocation -> {
              invocationStartMicros.set(DurableFunctionsUtils.nowMicros());
              Thread.sleep(25);
              throw new InvocationTargetException(new IllegalStateException("replay failure"));
            })
        .when(chain)
        .doNext(context);

    invokeOrchestration(
        context, singletonList(executionStarted()), singletonList(taskCompleted()), chain);

    writer.waitForTraces(1);
    DDSpan errorSpan = writer.firstTrace().get(0);
    assertEquals("42", errorSpan.getTraceId().toString());
    assertEquals(43L, errorSpan.getParentId());
    assertTrue(errorSpan.getStartTime() <= MICROSECONDS.toNanos(invocationStartMicros.get()));
    assertTrue(errorSpan.getDurationNano() >= MILLISECONDS.toNanos(20));

    assertTraces(
        trace(
            span()
                .childOf(43L)
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
              priority.set(activeSpan().spanContext().getSamplingPriority());
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
              priority.set(activeSpan().spanContext().getSamplingPriority());
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
              priority.set(activeSpan().spanContext().getSamplingPriority());
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
              priority.set(activeSpan().spanContext().getSamplingPriority());
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
              priority.set(activeSpan().spanContext().getSamplingPriority());
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
              priority.set(activeSpan().spanContext().getSamplingPriority());
              return null;
            })
        .when(chain)
        .doNext(context);

    invokeOrchestration(context, emptyList(), singletonList(executionStarted()), chain);

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
      assumeTrue(false, controlFlowTypeName + " is not available in this Durable Task version");
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

    invokeOrchestration(context, emptyList(), singletonList(executionStarted()), chain);

    assertTraces(trace(durableSpan("Orchestrator", "DurableOrchestration")));
  }

  @Test
  void marksDeeplyWrappedReplayInterruptionAsRealFailure() throws Exception {
    Throwable interruption = replayInterruption();
    assertFalse(
        DurableFunctionsUtils.isReplayControlFlow(
            new InvocationTargetException(new RuntimeException(interruption))));

    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    MiddlewareChain chain = mock(MiddlewareChain.class);
    doAnswer(
            invocation -> {
              throw new InvocationTargetException(new RuntimeException(interruption));
            })
        .when(chain)
        .doNext(context);

    invokeOrchestration(context, emptyList(), singletonList(executionStarted()), chain);

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
                    error(RuntimeException.class, interruption.toString()),
                    tag(Tags.COMPONENT, matches(Pattern.quote("azure-functions"))),
                    tag(Tags.SPAN_KIND, is(Tags.SPAN_KIND_SERVER)),
                    tag("aas.function.name", is("Orchestrator")),
                    tag("aas.function.trigger", is("DurableOrchestration")))));
  }

  @Test
  void marksUnwrappedReplayInterruptionAsRealFailure() throws Exception {
    Throwable interruption = replayInterruption();
    assertFalse(DurableFunctionsUtils.isReplayControlFlow(interruption));
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    MiddlewareChain chain = mock(MiddlewareChain.class);
    doAnswer(
            invocation -> {
              throw interruption;
            })
        .when(chain)
        .doNext(context);

    invokeOrchestration(context, emptyList(), singletonList(executionStarted()), chain);

    writer.waitForTraces(1);
    assertTrue(writer.firstTrace().get(0).isError());
  }

  @Test
  void marksWrappedApplicationFailuresAsErrors() throws Exception {
    MiddlewareContext context = contextFor("DurableActivityTrigger", "Activity");
    MiddlewareChain chain = mock(MiddlewareChain.class);
    doAnswer(
            invocation -> {
              throw new InvocationTargetException(new IllegalStateException("failure"));
            })
        .when(chain)
        .doNext(context);

    assertThrows(
        InvocationTargetException.class,
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

  private static ClassLoader isolatedWorkerLoader() {
    return isolatedWorkerLoader(false);
  }

  private static ClassLoader isolatedWorkerLoader(boolean hideDurableTask) {
    return new ClassLoader(AzureFunctionsWorkerTest.class.getClassLoader()) {
      @Override
      protected synchronized Class<?> loadClass(String name, boolean resolve)
          throws ClassNotFoundException {
        if (hideDurableTask && name.startsWith("com.microsoft.durabletask.")) {
          throw new ClassNotFoundException(name);
        }
        if (!name.startsWith("datadog.trace.instrumentation.azure.functions.worker.")) {
          return super.loadClass(name, resolve);
        }
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
          String resource = name.replace('.', '/') + ".class";
          try (InputStream input = getParent().getResourceAsStream(resource)) {
            if (input == null) {
              throw new ClassNotFoundException(name);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
              bytes.write(buffer, 0, count);
            }
            byte[] classBytes = bytes.toByteArray();
            loaded = defineClass(name, classBytes, 0, classBytes.length);
          } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
          }
        }
        if (resolve) {
          resolveClass(loaded);
        }
        return loaded;
      }
    };
  }

  @SuppressWarnings("unchecked")
  private static Throwable replayInterruption() throws Exception {
    for (String typeName :
        new String[] {
          "com.microsoft.durabletask.OrchestratorBlockedException",
          "com.microsoft.durabletask.interruption.OrchestratorBlockedException"
        }) {
      try {
        return instantiateThrowable((Class<? extends Throwable>) Class.forName(typeName));
      } catch (ClassNotFoundException ignored) {
        // The SDK moved the interruption class in newer versions.
      }
    }
    throw new AssertionError("No Durable Task replay interruption class found");
  }

  private static final class FailingOutput {
    public String getValue() {
      throw new IllegalStateException("serialization failure");
    }
  }

  public static final class NullActionsResult {
    public Collection<?> getActions() {
      return null;
    }
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
