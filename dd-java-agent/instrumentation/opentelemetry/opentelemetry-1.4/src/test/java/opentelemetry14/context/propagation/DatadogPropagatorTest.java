package opentelemetry14.context.propagation;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static java.lang.String.join;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.api.DDTraceId;
import datadog.trace.junit.utils.config.WithConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

@WithConfig(key = "trace.propagation.style", value = "datadog")
class DatadogPropagatorTest extends AgentPropagatorTest {
  static Stream<Arguments> values() {
    String traceIdDecimal = Long.toString(Long.parseLong("1111111111111111", 16));
    String parentIdDecimal = Long.toString(Long.parseLong("2222222222222222", 16));
    // spotless:off
    return Stream.of(
        arguments(
            headers(
                "x-datadog-trace-id", traceIdDecimal,
                "x-datadog-parent-id", parentIdDecimal),
            "1111111111111111",
            "2222222222222222",
            UNSET),
        arguments(
            headers(
                "x-datadog-trace-id", traceIdDecimal,
                "x-datadog-parent-id", parentIdDecimal,
                "x-datadog-tags", "_dd.p.tid=1111111111111111"),
            "11111111111111111111111111111111",
            "2222222222222222",
            UNSET),
        arguments(
            headers("x-datadog-trace-id", traceIdDecimal,
                "x-datadog-parent-id", parentIdDecimal,
                "x-datadog-sampling-priority", String.valueOf(SAMPLER_KEEP),
                "x-datadog-tags", "_dd.p.tid=1111111111111111"),
            "11111111111111111111111111111111",
            "2222222222222222",
            SAMPLER_KEEP),
        arguments(
            headers(
                "x-datadog-trace-id", traceIdDecimal,
                "x-datadog-parent-id", parentIdDecimal,
                "x-datadog-sampling-priority", String.valueOf(UNSET),
                "x-datadog-tags", "_dd.p.tid=1111111111111111"),
            "11111111111111111111111111111111",
            "2222222222222222",
            UNSET),
        arguments(
            headers(
                "x-datadog-trace-id", traceIdDecimal,
                "x-datadog-parent-id", parentIdDecimal,
                "x-datadog-sampling-priority", String.valueOf(SAMPLER_DROP),
                "x-datadog-tags", "_dd.p.tid=1111111111111111"),
            "11111111111111111111111111111111",
            "2222222222222222",
            SAMPLER_DROP));
    // spotless:on
  }

  @Override
  void assertInjectedHeaders(
      Map<String, String> headers, String traceId, String spanId, byte sampling) {
    assertEquals(
        Long.toString(DDTraceId.fromHex(traceId).toLong()), headers.get("x-datadog-trace-id"));
    assertEquals(spanId.replaceAll("^0+(?!$)", ""), headers.get("x-datadog-parent-id"));
    String samplingPriority = sampling == SAMPLER_DROP ? "0" : "1";
    List<String> tags = new ArrayList<>();
    if (sampling == UNSET) {
      tags.add("_dd.p.dm=-1");
    }
    if (traceId.length() == 32) {
      tags.add("_dd.p.tid=" + traceId.substring(0, 16));
    }
    if (sampling == UNSET) {
      tags.add("_dd.p.ksr=1");
    }
    assertEquals(join(",", tags), headers.get("x-datadog-tags"));
    assertEquals(samplingPriority, headers.get("x-datadog-sampling-priority"));
  }
}
