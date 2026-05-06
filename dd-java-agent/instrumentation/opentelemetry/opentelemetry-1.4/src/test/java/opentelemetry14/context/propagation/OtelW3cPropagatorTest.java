package opentelemetry14.context.propagation;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.junit.utils.config.WithConfig;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

@WithConfig(key = "trace.propagation.style", value = "datadog")
class OtelW3cPropagatorTest extends AbstractPropagatorTest {
  @Override
  TextMapPropagator propagator() {
    return W3CTraceContextPropagator.getInstance();
  }

  static Stream<Arguments> values() {
    return Stream.of(
        arguments(
            headers("traceparent", "00-00000000000000001111111111111111-2222222222222222-00"),
            "00000000000000001111111111111111",
            "2222222222222222",
            UNSET),
        arguments(
            headers("traceparent", "00-00000000000000001111111111111111-2222222222222222-01"),
            "00000000000000001111111111111111",
            "2222222222222222",
            SAMPLER_KEEP));
  }

  @Override
  void assertInjectedHeaders(
      Map<String, String> headers, String traceId, String spanId, byte sampling) {
    String sampleFlag = sampling == SAMPLER_KEEP ? "01" : "00";
    String expectedTraceParent = "00-" + traceId + "-" + spanId + "-" + sampleFlag;
    assertEquals(expectedTraceParent, headers.get("traceparent"));
  }
}
