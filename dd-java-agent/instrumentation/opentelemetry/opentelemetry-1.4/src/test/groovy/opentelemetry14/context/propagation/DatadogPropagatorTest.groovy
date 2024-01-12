package opentelemetry14.context.propagation


import datadog.trace.api.DDTraceId

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.UNSET
import static java.lang.Long.parseLong

class DatadogPropagatorTest extends AgentPropagatorTest {
  @Override
  String style() {
    return 'datadog'
  }

  def values() {
    // spotless:off
    return [
      [['x-datadog-trace-id': "${parseLong('1111111111111111', 16)}", 'x-datadog-parent-id': "${parseLong('2222222222222222', 16)}"],                                                                                                                 '1111111111111111', '2222222222222222', UNSET],
      [['x-datadog-trace-id': "${parseLong('1111111111111111', 16)}", 'x-datadog-parent-id': "${parseLong('2222222222222222', 16)}", 'x-datadog-tags': '_dd.p.tid=1111111111111111'],                                                 '11111111111111111111111111111111', '2222222222222222', UNSET],
      [['x-datadog-trace-id': "${parseLong('1111111111111111', 16)}", 'x-datadog-parent-id': "${parseLong('2222222222222222', 16)}", 'x-datadog-sampling-priority': "$SAMPLER_KEEP", 'x-datadog-tags': '_dd.p.tid=1111111111111111'], '11111111111111111111111111111111', '2222222222222222', SAMPLER_KEEP],
      [['x-datadog-trace-id': "${parseLong('1111111111111111', 16)}", 'x-datadog-parent-id': "${parseLong('2222222222222222', 16)}", 'x-datadog-sampling-priority': "$UNSET", 'x-datadog-tags': '_dd.p.tid=1111111111111111'],        '11111111111111111111111111111111', '2222222222222222', UNSET],
      [['x-datadog-trace-id': "${parseLong('1111111111111111', 16)}", 'x-datadog-parent-id': "${parseLong('2222222222222222', 16)}", 'x-datadog-sampling-priority': "$SAMPLER_DROP", 'x-datadog-tags': '_dd.p.tid=1111111111111111'], '11111111111111111111111111111111', '2222222222222222', SAMPLER_DROP],
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
