package opentelemetry14.context.propagation

import static datadog.trace.core.propagation.B3HttpCodec.B3_KEY

class B3SinglePropagatorTest extends AgentPropagatorTest {
  @Override
  String style() {
    return 'b3single'
  }

  @Override
  def values() {
    // spotless:off
    return [
      [[(B3_KEY): '1-2'], '1' , '2' , false],
      [[(B3_KEY): '1111111111111111-2222222222222222'],                   '1111111111111111',                 '2222222222222222', false],
      [[(B3_KEY): '11111111111111111111111111111111-2222222222222222'],   '11111111111111111111111111111111', '2222222222222222', false],
      [[(B3_KEY): '11111111111111111111111111111111-2222222222222222-0'], '11111111111111111111111111111111', '2222222222222222', false],
      [[(B3_KEY): '11111111111111111111111111111111-2222222222222222-1'], '11111111111111111111111111111111', '2222222222222222', true],
    ]
    // spotless:on
  }

  @Override
  void assertInjectedHeaders(Map<String, String> headers, String traceId, String spanId, boolean sampled) {
    def sampledValue = "1" // Deterministic sampler with rate to 1
    assert headers[B3_KEY] == "${traceId.padLeft(32, '0')}-${spanId.padLeft(8, '0')}-$sampledValue"
  }
}
