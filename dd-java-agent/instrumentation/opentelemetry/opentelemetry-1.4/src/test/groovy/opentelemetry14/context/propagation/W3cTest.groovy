package opentelemetry14.context.propagation

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDTraceId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.core.propagation.ExtractedContext
import datadog.trace.core.propagation.PropagationTags

// import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.TracePropagationStyle.TRACECONTEXT

class W3cTest extends AgentTestRunner {

  @Override
  void configurePreAgent() {
    // super.configurePreAgent()
    injectSysConfig("dd.integration.opentelemetry.experimental.enabled", "true")
    injectSysConfig("dd.trace.propagation.style", "tracecontext")
  }

  def "confirm w3c phase 2 lastParentId propagation"() {
    setup:
    def context = new ExtractedContext(DDTraceId.ONE, 2, PrioritySampling.SAMPLER_KEEP, "some-origin", "1234567890ABCDEF", 0, [:], [:], null,
      PropagationTags.factory().fromHeaderValue(PropagationTags.HeaderType.W3C, "_dd.p.dm=934086a686-4,_dd.p.mytag=myvalue"), null, TRACECONTEXT)

    def parentSpan = TEST_TRACER.buildSpan("testParent").asChildOf(context).start()
    def parentContext = parentSpan.context()

    def childSpan = TEST_TRACER.buildSpan("testChild").asChildOf(parentContext).start()
    def childContext = childSpan.context()


    expect:
    childContext instanceof datadog.trace.core.DDSpanContext  // passes
    ((datadog.trace.core.DDSpanContext) childContext).propagationTags.lastParentId == "1234567890ABCDEF"  // fails
    // check childSpan's tracestate contains lastParentId using propagator.extract()?

  }

}

