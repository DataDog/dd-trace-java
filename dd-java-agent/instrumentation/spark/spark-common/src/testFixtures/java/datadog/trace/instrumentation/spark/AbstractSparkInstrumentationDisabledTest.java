package datadog.trace.instrumentation.spark;

import static datadog.trace.api.config.GeneralConfig.DATA_JOBS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DATA_JOBS_EXPERIMENTAL_FEATURES_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DATA_JOBS_SPARK_SPAN_LINKS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Test;

/** Pre-agent coverage that feature flags cannot enable the default-off Spark instrumentation. */
@WithConfig(key = DATA_JOBS_ENABLED, value = "true")
@WithConfig(key = "integration.spark.enabled", value = "false")
@WithConfig(key = DATA_JOBS_SPARK_SPAN_LINKS, value = "true")
@WithConfig(key = DATA_JOBS_EXPERIMENTAL_FEATURES_ENABLED, value = "true")
public abstract class AbstractSparkInstrumentationDisabledTest extends AbstractInstrumentationTest {
  @Test
  void featureFlagsDoNotEnableSparkInstrumentation() throws Exception {
    SparkSession spark =
        SparkSession.builder()
            .master("local[2]")
            .appName("spark-instrumentation-disabled")
            .config("spark.default.parallelism", "2")
            .config("spark.sql.shuffle.partitions", "2")
            .config("spark.ui.enabled", "false")
            .getOrCreate();
    try {
      TestSparkComputation.generateTestSparkComputation(spark);
      spark.stop();

      assertNull(AbstractDatadogSparkListener.listener);
      assertFalse(writer.waitForTracesMax(1, 1));
    } finally {
      spark.stop();
      SparkSession.clearActiveSession();
      SparkSession.clearDefaultSession();
    }
  }
}
