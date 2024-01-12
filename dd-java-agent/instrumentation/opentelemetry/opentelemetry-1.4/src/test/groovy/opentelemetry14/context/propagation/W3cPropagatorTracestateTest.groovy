package opentelemetry14.context.propagation

import datadog.trace.agent.test.AgentTestRunner
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.context.Context
import spock.lang.Subject

class W3cPropagatorTracestateTest extends AgentTestRunner {
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
      .filter { !it.startsWith("dd=")}
      .toArray(String[]::new)
    }

    when:
    def context = propagator.extract(Context.root(), headers, new AbstractPropagatorTest.TextMap())

    then:
    context != Context.root()

    when:
    def localSpan = tracer.spanBuilder("some-name")
      .setParent(context)
      .startSpan()
    def scope = localSpan.makeCurrent()
    Map<String, String> injectedHeaders = [:]
    propagator.inject(Context.current(), injectedHeaders, new AbstractPropagatorTest.TextMap())
    scope.close()
    localSpan.end()

    then:
    // Check tracestate was injected
    def injectedTracestate = injectedHeaders['tracestate']
    injectedTracestate != null
    // Check tracestate contains extracted members plus the Datadog one in first position
    def injectedMembers = injectedTracestate.split(',')
    injectedMembers.length == Math.min(1 + members.length, 32)
    injectedMembers[0] == "dd=s:0;t.tid:1111111111111111"
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
