package opentelemetry14.context.propagation


import datadog.trace.core.propagation.B3TraceId

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.UNSET
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
      [[(B3_KEY): '1-2'],                                                 '1',                                '2',                UNSET],
      [[(B3_KEY): '1111111111111111-2222222222222222'],                   '1111111111111111',                 '2222222222222222', UNSET],
      [[(B3_KEY): '11111111111111111111111111111111-2222222222222222'],   '11111111111111111111111111111111', '2222222222222222', UNSET],
      [[(B3_KEY): '11111111111111111111111111111111-2222222222222222-0'], '11111111111111111111111111111111', '2222222222222222', SAMPLER_DROP],
      [[(B3_KEY): '11111111111111111111111111111111-2222222222222222-1'], '11111111111111111111111111111111', '2222222222222222', SAMPLER_KEEP],
    ]
    // spotless:on
  }

  @Override
  def expectedTraceId(String traceId) {
    return B3TraceId.fromHex(traceId)
  }

  @Override
  void assertInjectedHeaders(Map<String, String> headers, String traceId, String spanId, byte sampling) {
    def sampledValue = sampling == SAMPLER_DROP ? '0' : '1' // Deterministic sampler with rate to 1 if not explicitly dropped
    assert headers[B3_KEY] == "${traceId.padLeft(32, '0')}-${spanId.padLeft(8, '0')}-$sampledValue"
  }
}
