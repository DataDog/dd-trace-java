package datadog.trace.core.traceinterceptor;

import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.converter.TagsConverter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class LatencyTraceInterceptorTest extends DDCoreJavaSpecification {

  @TableTest({
    "scenario                                  | partialFlushEnabled | latencyThreshold | priorityTag          | minDuration | expected",
    "partial flush / keep / under threshold    | 'true'              | '200'            | 'DDTags.MANUAL_KEEP' | 10          | 2       ",
    "partial flush / drop / under threshold    | 'true'              | '200'            | 'DDTags.MANUAL_DROP' | 10          | -1      ",
    "partial flush / keep / over threshold     | 'true'              | '200'            | 'DDTags.MANUAL_KEEP' | 300         | 2       ",
    "partial flush / drop / over threshold     | 'true'              | '200'            | 'DDTags.MANUAL_DROP' | 300         | -1      ",
    "no partial flush / keep / under threshold | 'false'             | '200'            | 'DDTags.MANUAL_KEEP' | 10          | 2       ",
    "no partial flush / drop / under threshold | 'false'             | '200'            | 'DDTags.MANUAL_DROP' | 10          | -1      ",
    "no partial flush / keep / over threshold  | 'false'             | '200'            | 'DDTags.MANUAL_KEEP' | 300         | 2       ",
    "no partial flush / drop / over threshold  | 'false'             | '200'            | 'DDTags.MANUAL_DROP' | 300         | 2       "
  })
  void testSetSamplingPriorityAccordingToLatency(
      String partialFlushEnabled,
      String latencyThreshold,
      @ConvertWith(TagsConverter.class) String priorityTag,
      long minDuration,
      int expected)
      throws InterruptedException {
    injectSysConfig("trace.partial.flush.enabled", partialFlushEnabled);
    injectSysConfig("trace.experimental.keep.latency.threshold.ms", latencyThreshold);

    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();

    AgentSpan spanSetup =
        tracer.buildSpan("test", "my_operation_name").withTag(priorityTag, true).start();
    Thread.sleep(minDuration);
    spanSetup.finish();

    List<DDSpan> trace = writer.firstTrace();
    assertEquals(1, trace.size());
    DDSpan span = trace.get(0);
    assertEquals(expected, span.spanContext().getSamplingPriority());
  }
}
