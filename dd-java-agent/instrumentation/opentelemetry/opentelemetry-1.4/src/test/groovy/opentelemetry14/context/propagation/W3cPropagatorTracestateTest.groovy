package opentelemetry14.context.propagation

import datadog.trace.agent.test.InstrumentationSpecification
import io.opentelemetry.api.GlobalOpenTelemetry
import spock.lang.Subject

import static io.opentelemetry.context.Context.current
import static io.opentelemetry.context.Context.root

class W3cPropagatorTracestateTest extends InstrumentationSpecification {
  @Subject
  def tracer = GlobalOpenTelemetry.get().tracerProvider.get("tracecontext-propagator-tracestate")

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.opentelemetry.experimental.enabled", "true")
    injectSysConfig("dd.trace.propagation.style", "tracecontext")
  }

  def "test tracestate propagation"() {
    setup:
    // Get agent propagator injected by instrumentation
    def propagator = GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()
    def headers = [
      'traceparent': '00-11111111111111111111111111111111-2222222222222222-00'
    ]
    def members = new String[0]
    if (tracestate) {
      headers['tracestate'] = tracestate
      members = Arrays.stream(tracestate.split(','))
      .filter {
        !it.startsWith("dd=")
      }
      .toArray(String[]::new)
    }

    when:
    def context = propagator.extract(root(), headers, TextMap.INSTANCE)

    then:
    context != root()

    when:
    def localSpan = tracer.spanBuilder("some-name")
    .setParent(context)
    .startSpan()
    def scope = localSpan.makeCurrent()
    Map<String, String> injectedHeaders = [:]
    propagator.inject(current(), injectedHeaders, TextMap.INSTANCE)
    scope.close()
    localSpan.end()

    then:
    // Check tracestate was injected
    def injectedTracestate = injectedHeaders['tracestate']
    injectedTracestate != null
    // Check tracestate contains extracted members plus the Datadog one in first position
    def injectedMembers = injectedTracestate.split(',')
    injectedMembers.length == Math.min(1 + members.length, 32)
    // Check datadog member (should be injected as first member)
    injectedMembers[0] == "dd=s:0;p:${localSpan.spanContext.spanId};t.tid:1111111111111111"
    // Check all other members
    for (int i = 0; i< Math.min(members.length, 31); i++) {
      assert injectedMembers[i+1] == members[i]
    }

    where:
    // spotless:off
    tracestate << [
      "foo=1,bar=2",
      "dd=s:0,foo=1,bar=2",
      "foo=1,dd=s:0,bar=2",
    ]
    // spotless:on
  }
}
