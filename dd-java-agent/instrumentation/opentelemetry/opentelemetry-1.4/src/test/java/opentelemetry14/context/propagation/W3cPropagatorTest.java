package opentelemetry14.context.propagation;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.junit.utils.config.WithConfig;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

@WithConfig(key = "trace.propagation.style", value = "tracecontext")
class W3cPropagatorTest extends AgentPropagatorTest {
  static Stream<Arguments> values() {
    return Stream.of(
        arguments(
            headers("traceparent", "00-11111111111111111111111111111111-2222222222222222-00"),
            "11111111111111111111111111111111",
            "2222222222222222",
            UNSET),
        arguments(
            headers("traceparent", "00-11111111111111111111111111111111-2222222222222222-01"),
            "11111111111111111111111111111111",
            "2222222222222222",
            SAMPLER_KEEP));
  }

  @Override
  void assertInjectedHeaders(
      Map<String, String> headers, String traceId, String spanId, byte sampling) {
    String traceFlags = sampling == SAMPLER_KEEP ? "01" : "00";
    assertEquals("00-" + traceId + "-" + spanId + "-" + traceFlags, headers.get("traceparent"));
  }
}
