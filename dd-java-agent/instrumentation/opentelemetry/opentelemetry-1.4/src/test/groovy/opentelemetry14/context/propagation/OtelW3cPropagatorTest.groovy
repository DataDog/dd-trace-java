package opentelemetry14.context.propagation

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.TextMapPropagator

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.UNSET

class OtelW3cPropagatorTest extends AbstractPropagatorTest {
  @Override
  String style() {
    return 'datadog'
  }

  @Override
  TextMapPropagator propagator() {
    // OpenTelemetry API W3C propagator
    return W3CTraceContextPropagator.getInstance()
  }

  @Override
  def values() {
    // spotless:off
    return [
      [['traceparent': '00-00000000000000001111111111111111-2222222222222222-00'], '00000000000000001111111111111111', '2222222222222222', UNSET],
      [['traceparent': '00-00000000000000001111111111111111-2222222222222222-01'], '00000000000000001111111111111111', '2222222222222222', SAMPLER_KEEP],
    ]
    // spotless:on
  }

  @Override
  void assertInjectedHeaders(Map<String, String> headers, String traceId, String spanId, byte sampling) {
    def sampleFlag = sampling == SAMPLER_KEEP ? '01' : '00'
    assert headers['traceparent'] ==  "00-$traceId-$spanId-$sampleFlag"
  }
}
