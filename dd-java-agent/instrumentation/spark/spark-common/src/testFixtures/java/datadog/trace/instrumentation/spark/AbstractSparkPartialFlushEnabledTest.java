package datadog.trace.instrumentation.spark;

import static datadog.trace.api.config.GeneralConfig.DATA_JOBS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DATA_JOBS_SPARK_SPAN_LINKS;
import static datadog.trace.api.config.TracerConfig.PARTIAL_FLUSH_ENABLED;
import static datadog.trace.api.config.TracerConfig.PARTIAL_FLUSH_MIN_SPANS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Test;

/**
 * Verifies completed Spark descendants can be partially flushed while the application stays open.
 */
@WithConfig(key = DATA_JOBS_ENABLED, value = "true")
@WithConfig(key = "integration.spark.enabled", value = "true")
@WithConfig(key = DATA_JOBS_SPARK_SPAN_LINKS, value = "true")
@WithConfig(key = PARTIAL_FLUSH_ENABLED, value = "true")
@WithConfig(key = PARTIAL_FLUSH_MIN_SPANS, value = "1")
public abstract class AbstractSparkPartialFlushEnabledTest extends AbstractInstrumentationTest {
  @Test
  void flushesChildrenBeforeTheApplicationRootWithoutDuplicates() throws Exception {
    SparkSession spark =
        SparkSession.builder()
            .master("local[2]")
            .appName("spark-partial-flush-enabled")
            .config("spark.default.parallelism", "2")
            .config("spark.sql.shuffle.partitions", "2")
            .config("spark.ui.enabled", "false")
            .getOrCreate();
    try {
      TestSparkComputation.generateTestSparkComputation(spark);

      assertTrue(writer.waitForTracesMax(1, 10));
      List<DDSpan> beforeRoot = allReportedSpans();
      assertFalse(beforeRoot.isEmpty());
      assertFalse(hasOperation(beforeRoot, "spark.application"));

      spark.stop();
      blockUntilTracesMatch(traces ->
          hasOperation(
              traces.stream().flatMap(List::stream).collect(Collectors.toList()),
              "spark.application"));

      List<DDSpan> all = allReportedSpans();
      assertEquals(1, countOperation(all, "spark.application"));
      assertEquals(1, countOperation(all, "spark.job"));
      assertTrue(countOperation(all, "spark.stage") > 0);
      Set<Long> spanIds = all.stream().map(DDSpan::getSpanId).collect(Collectors.toSet());
      assertEquals(all.size(), spanIds.size(), "partial and final chunks must not duplicate spans");
      assertEquals(1, all.stream().map(DDSpan::getTraceId).collect(Collectors.toSet()).size());
    } finally {
      spark.stop();
      SparkSession.clearActiveSession();
      SparkSession.clearDefaultSession();
    }
  }

  private List<DDSpan> allReportedSpans() {
    return writer.stream().flatMap(List::stream).collect(Collectors.toList());
  }

  private static boolean hasOperation(List<DDSpan> spans, String operationName) {
    return countOperation(spans, operationName) > 0;
  }

  private static long countOperation(List<DDSpan> spans, String operationName) {
    return spans.stream()
        .filter(span -> operationName.contentEquals(span.getOperationName()))
        .count();
  }
}
