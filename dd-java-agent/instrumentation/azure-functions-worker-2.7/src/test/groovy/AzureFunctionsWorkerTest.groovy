import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.UNSET
import static org.mockito.ArgumentMatchers.anyString
import static org.mockito.Mockito.doAnswer
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.when

import com.microsoft.azure.functions.TraceContext
import com.microsoft.azure.functions.internal.spi.middleware.MiddlewareChain
import com.microsoft.azure.functions.internal.spi.middleware.MiddlewareContext
import com.microsoft.azure.functions.worker.chain.FunctionExecutionMiddleware
import com.microsoft.durabletask.interruption.ContinueAsNewInterruption
import com.microsoft.durabletask.interruption.OrchestratorBlockedException
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.lang.Unroll

abstract class AzureFunctionsWorkerTest extends VersionedNamingTestBase {

  @Override
  String service() {
    null
  }

  @Unroll
  def "creates a span for #trigger"() {
    setup:
    def context = contextFor(annotation, "MyFunction")
    def chain = mock(MiddlewareChain)

    when:
    new FunctionExecutionMiddleware().invoke(context, chain)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName operation()
          resourceName "$trigger MyFunction"
          spanType DDSpanTypes.SERVERLESS
          errored false
          tags {
            defaultTags()
            "$Tags.COMPONENT" "azure-functions"
            "$Tags.SPAN_KIND" "$Tags.SPAN_KIND_SERVER"
            "aas.function.name" "MyFunction"
            "aas.function.trigger" trigger
          }
        }
      }
    }

    where:
    annotation                    | trigger
    "DurableOrchestrationTrigger" | "DurableOrchestration"
    "DurableActivityTrigger"      | "DurableActivity"
    "DurableEntityTrigger"        | "DurableEntity"
  }

  def "does not create a span for a non-Durable function"() {
    setup:
    def context = contextFor(null, "HttpFunction")

    when:
    new FunctionExecutionMiddleware().invoke(context, mock(MiddlewareChain))

    then:
    assertTraces(0) {}
  }

  def "does not create a span for a successful orchestration replay"() {
    setup:
    def context = contextFor("DurableOrchestrationTrigger", "Orchestrator")
    // OrchestratorRequest { pastEvents: {}, newEvents: HistoryEvent { taskCompleted: {} } }
    when(context.getParameterValue("input")).thenReturn(
      Base64.encoder.encodeToString([0x1a, 0x00, 0x22, 0x02, 0x3a, 0x00] as byte[]))

    when:
    new FunctionExecutionMiddleware().invoke(context, mock(MiddlewareChain))

    then:
    assertTraces(0) {}
  }

  def "does not treat a failure field with the wrong protobuf wire type as a failure"() {
    setup:
    def context = contextFor("DurableOrchestrationTrigger", "Orchestrator")
    // OrchestratorRequest { pastEvents: {}, newEvents: HistoryEvent { field 8: varint 0 } }
    when(context.getParameterValue("input")).thenReturn(
      Base64.encoder.encodeToString([0x1a, 0x00, 0x22, 0x02, 0x40, 0x00] as byte[]))

    when:
    new FunctionExecutionMiddleware().invoke(context, mock(MiddlewareChain))

    then:
    assertTraces(0) {}
  }

  def "creates a span for an initial orchestration execution"() {
    setup:
    def context = contextFor("DurableOrchestrationTrigger", "Orchestrator")
    // OrchestratorRequest { newEvents: HistoryEvent { executionStarted: {} } }
    when(context.getParameterValue("input")).thenReturn(
      Base64.encoder.encodeToString([0x22, 0x02, 0x1a, 0x00] as byte[]))

    when:
    new FunctionExecutionMiddleware().invoke(context, mock(MiddlewareChain))

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName operation()
          resourceName "DurableOrchestration Orchestrator"
          spanType DDSpanTypes.SERVERLESS
          errored false
          tags {
            defaultTags()
            "$Tags.COMPONENT" "azure-functions"
            "$Tags.SPAN_KIND" "$Tags.SPAN_KIND_SERVER"
            "aas.function.name" "Orchestrator"
            "aas.function.trigger" "DurableOrchestration"
          }
        }
      }
    }
  }

  @Unroll
  def "creates a span for an orchestration replay with #failure"() {
    setup:
    def context = contextFor("DurableOrchestrationTrigger", "Orchestrator")
    // OrchestratorRequest { pastEvents: {}, newEvents: HistoryEvent { failure: {} } }
    when(context.getParameterValue("input")).thenReturn(
      Base64.encoder.encodeToString([0x1a, 0x00, 0x22, 0x02, failureTag, 0x00] as byte[]))

    when:
    new FunctionExecutionMiddleware().invoke(context, mock(MiddlewareChain))

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName operation()
          resourceName "DurableOrchestration Orchestrator"
          spanType DDSpanTypes.SERVERLESS
          errored false
          tags {
            defaultTags()
            "$Tags.COMPONENT" "azure-functions"
            "$Tags.SPAN_KIND" "$Tags.SPAN_KIND_SERVER"
            "aas.function.name" "Orchestrator"
            "aas.function.trigger" "DurableOrchestration"
          }
        }
      }
    }

    where:
    failure                    | failureTag
    "a failed activity"        | 0x42
    "a failed sub-orchestration" | 0x5a
  }

  @Unroll
  def "fails open for an unreadable orchestration payload: #description"() {
    setup:
    def context = contextFor("DurableOrchestrationTrigger", "Orchestrator")
    when(context.getParameterValue("input")).thenReturn(payload)

    when:
    new FunctionExecutionMiddleware().invoke(context, mock(MiddlewareChain))

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName operation()
          resourceName "DurableOrchestration Orchestrator"
          spanType DDSpanTypes.SERVERLESS
          errored false
          tags {
            defaultTags()
            "$Tags.COMPONENT" "azure-functions"
            "$Tags.SPAN_KIND" "$Tags.SPAN_KIND_SERVER"
            "aas.function.name" "Orchestrator"
            "aas.function.trigger" "DurableOrchestration"
          }
        }
      }
    }

    where:
    description          | payload
    "non-base64 input"   | "not base64"
    "truncated protobuf" | Base64.encoder.encodeToString([0x1a, 0x02, 0x00] as byte[])
    "non-string input"   | new Object()
  }

  def "continues the Azure trace and ignores its implicit sampling rejection"() {
    setup:
    def context = contextFor("DurableActivityTrigger", "Activity")
    def traceContext = mock(TraceContext)
    when(traceContext.getTraceparent()).thenReturn(
      "00-0000000000000000000000000000002a-000000000000002b-00")
    when(traceContext.getTracestate()).thenReturn(null)
    when(context.getTraceContext()).thenReturn(traceContext)
    def priority = Integer.MIN_VALUE
    def chain = mock(MiddlewareChain)
    doAnswer {
      priority = AgentTracer.activeSpan().spanContext().samplingPriority
      null
    }.when(chain).doNext(context)

    when:
    new FunctionExecutionMiddleware().invoke(context, chain)

    then:
    TEST_WRITER.waitForTraces(1)
    def span = TEST_WRITER[0][0]
    span.traceId.toString() == "42"
    span.parentId == 43
    priority == UNSET
    span.samplingPriority() == SAMPLER_KEEP
  }

  def "honors a Datadog keep decision when Azure clears the W3C sampled flag"() {
    setup:
    def context = contextFor("DurableOrchestrationTrigger", "Orchestrator")
    def traceContext = mock(TraceContext)
    when(traceContext.getTraceparent()).thenReturn(
      "00-0000000000000000000000000000002a-000000000000002b-00")
    when(traceContext.getTracestate()).thenReturn("dd=s:2;o:rum")
    when(context.getTraceContext()).thenReturn(traceContext)
    def priority = Integer.MIN_VALUE
    def chain = mock(MiddlewareChain)
    doAnswer {
      priority = AgentTracer.activeSpan().spanContext().samplingPriority
      null
    }.when(chain).doNext(context)

    when:
    new FunctionExecutionMiddleware().invoke(context, chain)

    then:
    TEST_WRITER.waitForTraces(1)
    def span = TEST_WRITER[0][0]
    span.traceId.toString() == "42"
    span.parentId == 43
    priority > 0
  }

  @Unroll
  def "does not mark #controlFlow.class.simpleName as an error"() {
    setup:
    def context = contextFor("DurableOrchestrationTrigger", "Orchestrator")
    def chain = mock(MiddlewareChain)
    doAnswer { throw new RuntimeException(controlFlow) }.when(chain).doNext(context)

    when:
    new FunctionExecutionMiddleware().invoke(context, chain)

    then:
    thrown(RuntimeException)
    assertTraces(1) {
      trace(1) {
        span {
          operationName operation()
          resourceName "DurableOrchestration Orchestrator"
          spanType DDSpanTypes.SERVERLESS
          errored false
          tags {
            defaultTags()
            "$Tags.COMPONENT" "azure-functions"
            "$Tags.SPAN_KIND" "$Tags.SPAN_KIND_SERVER"
            "aas.function.name" "Orchestrator"
            "aas.function.trigger" "DurableOrchestration"
          }
        }
      }
    }

    where:
    controlFlow << [
      new com.microsoft.durabletask.OrchestratorBlockedException(),
      new OrchestratorBlockedException(),
      new ContinueAsNewInterruption()
    ]
  }

  def "marks application failures as errors"() {
    setup:
    def context = contextFor("DurableActivityTrigger", "Activity")
    def chain = mock(MiddlewareChain)
    doAnswer { throw new IllegalStateException("failure") }.when(chain).doNext(context)

    when:
    new FunctionExecutionMiddleware().invoke(context, chain)

    then:
    thrown(IllegalStateException)
    assertTraces(1) {
      trace(1) {
        span {
          operationName operation()
          resourceName "DurableActivity Activity"
          spanType DDSpanTypes.SERVERLESS
          errored true
          tags {
            errorTags IllegalStateException, "failure"
            defaultTags()
            "$Tags.COMPONENT" "azure-functions"
            "$Tags.SPAN_KIND" "$Tags.SPAN_KIND_SERVER"
            "aas.function.name" "Activity"
            "aas.function.trigger" "DurableActivity"
          }
        }
      }
    }
  }

  private static MiddlewareContext contextFor(String annotation, String functionName) {
    def context = mock(MiddlewareContext)
    when(context.getFunctionName()).thenReturn(functionName)
    when(context.getParameterName(anyString())).thenAnswer {
      it.arguments[0] == annotation ? "input" : null
    }
    context
  }
}

class AzureFunctionsWorkerV0ForkedTest extends AzureFunctionsWorkerTest {
  @Override
  int version() {
    0
  }

  @Override
  String operation() {
    "dd-tracer-serverless-span"
  }
}

class AzureFunctionsWorkerV1Test extends AzureFunctionsWorkerTest {
  @Override
  int version() {
    1
  }

  @Override
  String operation() {
    "azure.functions.invoke"
  }
}
