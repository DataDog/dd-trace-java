package datadog.trace.core.traceinterceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.DDTags;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(10)
class LatencyTraceInterceptorTest extends DDCoreSpecification {

  static Stream<Arguments> testSetSamplingPriorityAccordingToLatencyArguments() {
    return Stream.of(
        Arguments.of("true", "200", DDTags.MANUAL_KEEP, 10, 2),
        Arguments.of("true", "200", DDTags.MANUAL_DROP, 10, -1),
        Arguments.of("true", "200", DDTags.MANUAL_KEEP, 300, 2),
        Arguments.of("true", "200", DDTags.MANUAL_DROP, 300, -1),
        Arguments.of("false", "200", DDTags.MANUAL_KEEP, 10, 2),
        Arguments.of("false", "200", DDTags.MANUAL_DROP, 10, -1),
        Arguments.of("false", "200", DDTags.MANUAL_KEEP, 300, 2),
        Arguments.of("false", "200", DDTags.MANUAL_DROP, 300, 2));
  }

  @ParameterizedTest
  @MethodSource("testSetSamplingPriorityAccordingToLatencyArguments")
  void testSetSamplingPriorityAccordingToLatency(
      String partialFlushEnabled,
      String latencyThreshold,
      String priorityTag,
      long minDuration,
      int expected)
      throws Exception {
    injectSysConfig("trace.partial.flush.enabled", partialFlushEnabled);
    injectSysConfig("trace.experimental.keep.latency.threshold.ms", latencyThreshold);

    ListWriter writer = new ListWriter();
    datadog.trace.core.CoreTracer tracer = tracerBuilder().writer(writer).build();

    datadog.trace.bootstrap.instrumentation.api.AgentSpan spanSetup =
        tracer.buildSpan("test", "my_operation_name").withTag(priorityTag, true).start();
    Thread.sleep(minDuration);
    spanSetup.finish();

    List trace = writer.firstTrace();
    assertEquals(1, trace.size());
    datadog.trace.core.DDSpan span = (datadog.trace.core.DDSpan) trace.get(0);
    assertEquals(expected, span.context().getSamplingPriority());

    tracer.close();
  }
}
