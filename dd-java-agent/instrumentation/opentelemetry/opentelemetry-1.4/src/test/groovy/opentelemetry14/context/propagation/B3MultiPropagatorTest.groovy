package opentelemetry14.context.propagation

import datadog.trace.core.propagation.B3TraceId

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.UNSET
import static datadog.trace.core.propagation.B3HttpCodec.SAMPLING_PRIORITY_KEY
import static datadog.trace.core.propagation.B3HttpCodec.SPAN_ID_KEY
import static datadog.trace.core.propagation.B3HttpCodec.TRACE_ID_KEY

class B3MultiPropagatorTest extends AgentPropagatorTest {
  @Override
  String style() {
    return 'b3multi'
  }

  @Override
  def values() {
    // spotless:off
    return [
      [[(TRACE_ID_KEY): '1', (SPAN_ID_KEY):'2'],                                                                             '1' ,                               '2' ,               UNSET],
      [[(TRACE_ID_KEY): '1111111111111111', (SPAN_ID_KEY):'2222222222222222'],                                               '1111111111111111'                , '2222222222222222', UNSET],
      [[(TRACE_ID_KEY): '11111111111111111111111111111111', (SPAN_ID_KEY):'2222222222222222'],                               '11111111111111111111111111111111', '2222222222222222', UNSET],
      [[(TRACE_ID_KEY): '11111111111111111111111111111111', (SPAN_ID_KEY):'2222222222222222', (SAMPLING_PRIORITY_KEY): '0'], '11111111111111111111111111111111', '2222222222222222', SAMPLER_DROP],
      [[(TRACE_ID_KEY): '11111111111111111111111111111111', (SPAN_ID_KEY):'2222222222222222', (SAMPLING_PRIORITY_KEY): '1'], '11111111111111111111111111111111', '2222222222222222', SAMPLER_KEEP],
    ]
    // spotless:on
  }

  @Override
  def expectedTraceId(String traceId) {
    return B3TraceId.fromHex(traceId)
  }

  @Override
  void assertInjectedHeaders(Map<String, String> headers, String traceId, String spanId, byte sampling) {
    def priorityKey = sampling == SAMPLER_DROP ? '0' : '1' // Deterministic sampler with rate to 1 if not explicitly dropped
    assert headers[TRACE_ID_KEY] == traceId.padLeft(32, '0')
    assert headers[SPAN_ID_KEY] == spanId.padLeft(8, '0')
    assert headers[SAMPLING_PRIORITY_KEY] == priorityKey
  }
}
