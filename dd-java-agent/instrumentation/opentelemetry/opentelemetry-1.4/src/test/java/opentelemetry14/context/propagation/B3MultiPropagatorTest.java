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

@WithConfig(key = "trace.propagation.style", value = "b3multi")
class B3MultiPropagatorTest extends AgentPropagatorTest {
  private static final String TRACE_ID_KEY = "X-B3-TraceId";
  private static final String SPAN_ID_KEY = "X-B3-SpanId";
  private static final String SAMPLING_PRIORITY_KEY = "X-B3-Sampled";

  static Stream<Arguments> values() {
    // spotless:off
    return Stream.of(
        arguments(
            headers(TRACE_ID_KEY, "1", SPAN_ID_KEY, "2"),
            "1",
            "2",
            UNSET),
        arguments(
            headers(TRACE_ID_KEY, "1111111111111111", SPAN_ID_KEY, "2222222222222222"),
            "1111111111111111",
            "2222222222222222",
            UNSET),
        arguments(
            headers(TRACE_ID_KEY, "11111111111111111111111111111111", SPAN_ID_KEY, "2222222222222222"),
            "11111111111111111111111111111111",
            "2222222222222222",
            UNSET),
        arguments(
            headers(TRACE_ID_KEY, "11111111111111111111111111111111", SPAN_ID_KEY, "2222222222222222",
                SAMPLING_PRIORITY_KEY, "0"),
            "11111111111111111111111111111111",
            "2222222222222222",
            SAMPLER_DROP),
        arguments(
            headers(TRACE_ID_KEY, "11111111111111111111111111111111",
                SPAN_ID_KEY, "2222222222222222",
                SAMPLING_PRIORITY_KEY, "1"),
            "11111111111111111111111111111111",
            "2222222222222222",
            SAMPLER_KEEP));
    // spotless:on
  }

  @Override
  Object expectedTraceId(String traceId) {
    return B3TraceId.fromHex(traceId);
  }

  @Override
  void assertInjectedHeaders(
      Map<String, String> headers, String traceId, String spanId, byte sampling) {
    String priorityKey = sampling == SAMPLER_DROP ? "0" : "1";
    assertEquals(zeroPadLeft(traceId, 32), headers.get(TRACE_ID_KEY));
    assertEquals(zeroPadLeft(spanId, 8), headers.get(SPAN_ID_KEY));
    assertEquals(priorityKey, headers.get(SAMPLING_PRIORITY_KEY));
  }
}
