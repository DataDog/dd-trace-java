package datadog.trace.instrumentation.spark;

import static datadog.trace.api.config.GeneralConfig.DATA_JOBS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DATA_JOBS_SPARK_SPAN_LINKS;
import static datadog.trace.api.config.TracerConfig.PARTIAL_FLUSH_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.List;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Test;

/**
 * Verifies disabling partial flush buffers Spark descendants until the application root finishes.
 */
@WithConfig(key = DATA_JOBS_ENABLED, value = "true")
@WithConfig(key = "integration.spark.enabled", value = "true")
@WithConfig(key = DATA_JOBS_SPARK_SPAN_LINKS, value = "true")
@WithConfig(key = PARTIAL_FLUSH_ENABLED, value = "false")
public abstract class AbstractSparkPartialFlushDisabledTest extends AbstractInstrumentationTest {
  @Test
  void buffersChildrenUntilTheApplicationRootFinishes() throws Exception {
    SparkSession spark =
        SparkSession.builder()
            .master("local[2]")
            .appName("spark-partial-flush-disabled")
            .config("spark.default.parallelism", "2")
            .config("spark.sql.shuffle.partitions", "2")
            .config("spark.ui.enabled", "false")
            .getOrCreate();
    try {
      TestSparkComputation.generateTestSparkComputation(spark);
      assertFalse(writer.waitForTracesMax(1, 1));

      spark.stop();
      writer.waitForTraces(1);

      assertEquals(1, writer.size());
      List<DDSpan> trace = writer.get(0);
      assertEquals(1, countOperation(trace, "spark.application"));
      assertEquals(1, countOperation(trace, "spark.job"));
      assertTrue(countOperation(trace, "spark.stage") > 0);
    } finally {
      spark.stop();
      SparkSession.clearActiveSession();
      SparkSession.clearDefaultSession();
    }
  }

  private static long countOperation(List<DDSpan> spans, String operationName) {
    return spans.stream()
        .filter(span -> operationName.contentEquals(span.getOperationName()))
        .count();
  }
}
