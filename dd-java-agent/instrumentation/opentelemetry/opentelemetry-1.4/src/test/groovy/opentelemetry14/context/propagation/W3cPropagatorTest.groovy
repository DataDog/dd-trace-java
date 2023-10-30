package opentelemetry14.context.propagation

class W3cPropagatorTest extends AgentPropagatorTest {
  @Override
  String style() {
    return 'tracecontext'
  }

  @Override
  def values() {
    // spotless:off
    return [
      [['traceparent': '00-11111111111111111111111111111111-2222222222222222-00'], '11111111111111111111111111111111', '2222222222222222', false],
      [['traceparent': '00-11111111111111111111111111111111-2222222222222222-01'], '11111111111111111111111111111111', '2222222222222222', true],
    ]
    // spotless:on
  }

  @Override
  void assertInjectedHeaders(Map<String, String> headers, String traceId, String spanId, boolean sampled) {
    def traceFlags = "01" // Deterministic sampler with rate to 1
    assert headers['traceparent'] == "00-$traceId-$spanId-$traceFlags"
  }
}
