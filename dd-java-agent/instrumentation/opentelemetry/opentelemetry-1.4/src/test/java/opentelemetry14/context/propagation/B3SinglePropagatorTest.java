package opentelemetry14.context.propagation;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.core.propagation.B3TraceId;
import datadog.trace.junit.utils.config.WithConfig;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

@WithConfig(key = "trace.propagation.style", value = "b3single")
class B3SinglePropagatorTest extends AgentPropagatorTest {
  private static final String B3_KEY = "b3";

  static Stream<Arguments> values() {
    // spotless:off
    return Stream.of(
        arguments(headers(B3_KEY, "1-2"),
            "1",
            "2",
            UNSET),
        arguments(
            headers(B3_KEY, "1111111111111111-2222222222222222"),
            "1111111111111111",
            "2222222222222222",
            UNSET),
        arguments(
            headers(B3_KEY, "11111111111111111111111111111111-2222222222222222"),
            "11111111111111111111111111111111",
            "2222222222222222",
            UNSET),
        arguments(
            headers(B3_KEY, "11111111111111111111111111111111-2222222222222222-0"),
            "11111111111111111111111111111111",
            "2222222222222222",
            SAMPLER_DROP),
        arguments(
            headers(B3_KEY, "11111111111111111111111111111111-2222222222222222-1"),
            "11111111111111111111111111111111",
            "2222222222222222",
            SAMPLER_KEEP));
    // spotless:on
  }

  @Override
  String expectedTraceId(String traceId) {
    return B3TraceId.fromHex(traceId).toString();
  }

  @Override
  void assertInjectedHeaders(
      Map<String, String> headers, String traceId, String spanId, byte sampling) {
    String sampledValue = sampling == SAMPLER_DROP ? "0" : "1";
    String expected = zeroPadLeft(traceId, 32) + "-" + zeroPadLeft(spanId, 8) + "-" + sampledValue;
    assertEquals(expected, headers.get(B3_KEY));
  }
}
