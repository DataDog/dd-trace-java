import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;
import static datadog.trace.test.junit.utils.assertions.Matchers.matches;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.microsoft.azure.functions.TraceContext;
import com.microsoft.azure.functions.internal.spi.middleware.MiddlewareChain;
import com.microsoft.azure.functions.internal.spi.middleware.MiddlewareContext;
import com.microsoft.azure.functions.worker.chain.FunctionExecutionMiddleware;
import com.microsoft.durabletask.interruption.ContinueAsNewInterruption;
import com.microsoft.durabletask.interruption.OrchestratorBlockedException;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

abstract class AzureFunctionsWorkerTest extends AbstractInstrumentationTest {

  abstract String operation();

  // spotless:off
  @TableTest({
    "scenario      | annotation                  | trigger",
    "orchestration | DurableOrchestrationTrigger | DurableOrchestration",
    "activity      | DurableActivityTrigger      | DurableActivity",
    "entity        | DurableEntityTrigger        | DurableEntity"
  })
  // spotless:on
  void createsSpanForDurableTrigger(String annotation, String trigger) throws Exception {
    MiddlewareContext context = contextFor(annotation, "MyFunction");

    new FunctionExecutionMiddleware().invoke(context, mock(MiddlewareChain.class));

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
    // OrchestratorRequest { pastEvents: {}, newEvents: HistoryEvent { taskCompleted: {} } }
    when(context.getParameterValue("input"))
        .thenReturn(
            Base64.getEncoder().encodeToString(new byte[] {0x1a, 0x00, 0x22, 0x02, 0x3a, 0x00}));

    new FunctionExecutionMiddleware().invoke(context, mock(MiddlewareChain.class));

    assertTraces();
  }

  @Test
  void doesNotTreatFailureFieldWithWrongProtobufWireTypeAsFailure() throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    // OrchestratorRequest { pastEvents: {}, newEvents: HistoryEvent { field 8: varint 0 } }
    when(context.getParameterValue("input"))
        .thenReturn(
            Base64.getEncoder().encodeToString(new byte[] {0x1a, 0x00, 0x22, 0x02, 0x40, 0x00}));

    new FunctionExecutionMiddleware().invoke(context, mock(MiddlewareChain.class));

    assertTraces();
  }

  @Test
  void suppressesReplayWithLargeHistory() throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    byte[] request = new byte[4103];
    // OrchestratorRequest { pastEvents: <4096 bytes>, newEvents: taskCompleted {} }
    request[0] = 0x1a;
    request[1] = (byte) 0x80;
    request[2] = 0x20;
    request[4099] = 0x22;
    request[4100] = 0x02;
    request[4101] = 0x3a;
    request[4102] = 0x00;
    when(context.getParameterValue("input"))
        .thenReturn(Base64.getEncoder().encodeToString(request));

    new FunctionExecutionMiddleware().invoke(context, mock(MiddlewareChain.class));

    assertTraces();
  }

  @Test
  void createsSpanForInitialOrchestrationExecution() throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    // OrchestratorRequest { newEvents: HistoryEvent { executionStarted: {} } }
    when(context.getParameterValue("input"))
        .thenReturn(Base64.getEncoder().encodeToString(new byte[] {0x22, 0x02, 0x1a, 0x00}));

    new FunctionExecutionMiddleware().invoke(context, mock(MiddlewareChain.class));

    assertTraces(trace(durableSpan("Orchestrator", "DurableOrchestration")));
  }

  // spotless:off
  @TableTest({
    "scenario                 | failureTag",
    "failed activity          | 66",
    "failed sub-orchestration | 90"
  })
  // spotless:on
  void createsSpanForOrchestrationReplayWithFailure(int failureTag) throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    // OrchestratorRequest { pastEvents: {}, newEvents: HistoryEvent { failure: {} } }
    when(context.getParameterValue("input"))
        .thenReturn(
            Base64.getEncoder()
                .encodeToString(new byte[] {0x1a, 0x00, 0x22, 0x02, (byte) failureTag, 0x00}));

    new FunctionExecutionMiddleware().invoke(context, mock(MiddlewareChain.class));

    assertTraces(trace(durableSpan("Orchestrator", "DurableOrchestration")));
  }

  @Test
  void createsErrorSpanWhenSuppressedOrchestrationReplayFails() throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    // OrchestratorRequest { pastEvents: {}, newEvents: HistoryEvent { taskCompleted: {} } }
    when(context.getParameterValue("input"))
        .thenReturn(
            Base64.getEncoder().encodeToString(new byte[] {0x1a, 0x00, 0x22, 0x02, 0x3a, 0x00}));
    MiddlewareChain chain = mock(MiddlewareChain.class);
    doAnswer(
            invocation -> {
              throw new IllegalStateException("replay failure");
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

  @ParameterizedTest(name = "{0}")
  // spotless:off
  @TableTest({
    "scenario           | description        | payload",
    "non-base64 input   | non-base64 input   | 'not base64'",
    "truncated protobuf | truncated protobuf | GgIA"
  })
  // spotless:on
  @MethodSource("failsOpenForUnreadableOrchestrationPayloadArguments")
  void failsOpenForUnreadableOrchestrationPayload(String description, Object payload)
      throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    when(context.getParameterValue("input")).thenReturn(payload);

    new FunctionExecutionMiddleware().invoke(context, mock(MiddlewareChain.class));

    assertTraces(trace(durableSpan("Orchestrator", "DurableOrchestration")));
  }

  static Stream<Arguments> failsOpenForUnreadableOrchestrationPayloadArguments() {
    return Stream.of(arguments("non-string input", new Object()));
  }

  @Test
  void continuesAzureTraceAndIgnoresImplicitSamplingRejection() throws Exception {
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
    assertEquals(UNSET, priority.get());
    assertEquals(SAMPLER_KEEP, span.samplingPriority());
  }

  @Test
  void honorsDatadogKeepDecisionWhenAzureClearsW3cSampledFlag() throws Exception {
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

    new FunctionExecutionMiddleware().invoke(context, chain);

    writer.waitForTraces(1);
    DDSpan span = writer.firstTrace().get(0);
    assertEquals("42", span.getTraceId().toString());
    assertEquals(43L, span.getParentId());
    assertTrue(priority.get() > 0);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("doesNotMarkReplayControlFlowAsErrorArguments")
  void doesNotMarkReplayControlFlowAsError(String description, Throwable controlFlow)
      throws Exception {
    MiddlewareContext context = contextFor("DurableOrchestrationTrigger", "Orchestrator");
    MiddlewareChain chain = mock(MiddlewareChain.class);
    doAnswer(
            invocation -> {
              throw new RuntimeException(controlFlow);
            })
        .when(chain)
        .doNext(context);

    assertThrows(
        RuntimeException.class, () -> new FunctionExecutionMiddleware().invoke(context, chain));

    assertTraces(trace(durableSpan("Orchestrator", "DurableOrchestration")));
  }

  static Stream<Arguments> doesNotMarkReplayControlFlowAsErrorArguments() {
    return Stream.of(
        arguments(
            "legacy OrchestratorBlockedException",
            new com.microsoft.durabletask.OrchestratorBlockedException()),
        arguments("OrchestratorBlockedException", new OrchestratorBlockedException()),
        arguments("ContinueAsNewInterruption", new ContinueAsNewInterruption()));
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

  private static MiddlewareContext contextFor(String annotation, String functionName) {
    MiddlewareContext context = mock(MiddlewareContext.class);
    when(context.getFunctionName()).thenReturn(functionName);
    when(context.getParameterName(anyString()))
        .thenAnswer(
            invocation -> Objects.equals(invocation.getArgument(0), annotation) ? "input" : null);
    return context;
  }
}
