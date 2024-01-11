package opentelemetry14.context.propagation

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.UNSET

class W3cPropagatorTest extends AgentPropagatorTest {
  @Override
  String style() {
    return 'tracecontext'
  }

  @Override
  def values() {
    // spotless:off
    return [
      [['traceparent': '00-11111111111111111111111111111111-2222222222222222-00'], '11111111111111111111111111111111', '2222222222222222', UNSET],
      [['traceparent': '00-11111111111111111111111111111111-2222222222222222-01'], '11111111111111111111111111111111', '2222222222222222', SAMPLER_KEEP],
    ]
    // spotless:on
  }

  @Override
  void assertInjectedHeaders(Map<String, String> headers, String traceId, String spanId, byte sampling) {
    def traceFlags = sampling == SAMPLER_KEEP ? '01' : '00'
    assert headers['traceparent'] == "00-$traceId-$spanId-$traceFlags"
  }
}
