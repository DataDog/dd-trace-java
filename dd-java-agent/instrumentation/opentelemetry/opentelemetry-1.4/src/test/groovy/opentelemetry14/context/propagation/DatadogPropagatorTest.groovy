package opentelemetry14.context.propagation


import datadog.trace.api.DDTraceId

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
      [['x-datadog-trace-id': "${parseLong('1111111111111111', 16)}", 'x-datadog-parent-id': "${parseLong('2222222222222222', 16)}"],                                                                                                 '00000000000000001111111111111111', '2222222222222222', false],
      [['x-datadog-trace-id': "${parseLong('1111111111111111', 16)}", 'x-datadog-parent-id': "${parseLong('2222222222222222', 16)}", 'x-datadog-tags': '_dd.p.tid=1111111111111111'],                                                 '11111111111111111111111111111111', '2222222222222222', false],
      [['x-datadog-trace-id': "${parseLong('1111111111111111', 16)}", 'x-datadog-parent-id': "${parseLong('2222222222222222', 16)}", 'x-datadog-sampling-priority': "$SAMPLER_KEEP", 'x-datadog-tags': '_dd.p.tid=1111111111111111'], '11111111111111111111111111111111', '2222222222222222', true],
      [['x-datadog-trace-id': "${parseLong('1111111111111111', 16)}", 'x-datadog-parent-id': "${parseLong('2222222222222222', 16)}", 'x-datadog-sampling-priority': "$UNSET", 'x-datadog-tags': '_dd.p.tid=1111111111111111'],        '11111111111111111111111111111111', '2222222222222222', false],
    ]
    // spotless:on
  }

  void assertInjectedHeaders(Map<String, String> headers, String traceId, String spanId, boolean sampled) {
    assert headers['x-datadog-trace-id'] == Long.toString(DDTraceId.fromHex(traceId).toLong())
    assert headers['x-datadog-parent-id'] == spanId.replaceAll('^0+(?!$)', '')
    def tags = []
    if (!sampled) {
      tags+= '_dd.p.dm=-1'
    }
    def highOrderBits = traceId.substring(0, 16)
    if (highOrderBits != '0000000000000000') {
      tags+= '_dd.p.tid='+highOrderBits
    }
    assert headers['x-datadog-tags'] == tags.join(',')
    assert headers['x-datadog-sampling-priority'] == '1' // Deterministic sampler with rate to 1
  }
}
