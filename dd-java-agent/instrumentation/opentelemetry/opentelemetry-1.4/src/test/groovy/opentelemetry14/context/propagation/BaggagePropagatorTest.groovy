package opentelemetry14.context.propagation


import datadog.trace.api.DDTraceId

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.UNSET
import static java.lang.Long.parseLong

class BaggagePropagatorTest extends AgentPropagatorTest {
  @Override
  String style() {
    return 'baggage'
  }

  def values() {
    // spotless:off
    return [
      [["baggage": "key1=val1,key2=val2,foo=bar"], '00000000000000001111111111111111', '2222222222222222', UNSET],
    ]
    // spotless:on
  }

  void assertInjectedHeaders(Map<String, String> headers, String traceId, String spanId, byte sampling) {
    assert headers['x-datadog-trace-id'] == Long.toString(DDTraceId.fromHex(traceId).toLong())
    assert headers['x-datadog-parent-id'] == spanId.replaceAll('^0+(?!$)', '')
    def tags = []
    def samplingPriority = sampling == SAMPLER_DROP ? '0' : '1' // Deterministic sampler with rate to 1 if not explicitly dropped
    if (sampling == UNSET) {
      tags+= '_dd.p.dm=-1'
    }
    if (traceId.length() == 32) {
      tags+= '_dd.p.tid='+ traceId.substring(0, 16)
    }
    assert headers['x-datadog-tags'] == tags.join(',')
    assert headers['x-datadog-sampling-priority'] == samplingPriority
  }
}
