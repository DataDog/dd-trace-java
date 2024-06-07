package opentelemetry14.context.propagation

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP

class W3cLastParentIdTest extends AgentPropagatorTest {
  @Override
  String style() {
    return 'tracecontext'
  }

  @Override
  def values() {
    // spotless:off
    return [
      [['traceparent': '00-11111111111111111111111111111111-2222222222222222-01','tracestate': 'dd=s:2;p:1948e0b51aee0bfa'], '11111111111111111111111111111111', '2222222222222222', SAMPLER_KEEP]
    ]
    // spotless:on
  }

  @Override
  void assertInjectedHeaders(Map<String, String> headers, String traceId, String spanId, byte sampling) {
    assert headers['tracestate'].contains("p:1948e0b51aee0bfa")
  }
}
