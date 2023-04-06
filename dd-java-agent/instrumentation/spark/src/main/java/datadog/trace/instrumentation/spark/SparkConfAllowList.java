package datadog.trace.instrumentation.spark;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Helper class to only capture a selected subset of the spark configuration
 *
 * @see <a href="https://spark.apache.org/docs/latest/configuration.html">Spark Configuration</a>
 */
class SparkConfAllowList {
  private static final Set<String> allowedParams =
      new HashSet<>(
          Arrays.asList(
              "spark.dynamicAllocation.enabled",
              "spark.dynamicAllocation.executorIdleTimeout",
              "spark.dynamicAllocation.initialExecutors",
              "spark.dynamicAllocation.maxExecutors",
              "spark.dynamicAllocation.minExecutors",
              "spark.dynamicAllocation.executorAllocationRatio",
              "spark.dynamicAllocation.schedulerBacklogTimeout",
              "spark.driver.cores",
              "spark.driver.maxResultSize",
              "spark.driver.memory",
              "spark.driver.memoryOverhead",
              "spark.driver.memoryOverheadFactor",
              "spark.executor.cores",
              "spark.executor.memory",
              "spark.executor.pyspark.memory",
              "spark.executor.memoryOverhead",
              "spark.executor.memoryOverheadFactor",
              "spark.master",
              "spark.memory.fraction",
              "spark.memory.storageFraction",
              "spark.memory.offHeap.enabled",
              "spark.memory.offHeap.size",
              "spark.submit.deployMode",
              "spark.sql.autoBroadcastJoinThreshold",
              "spark.sql.execution.id",
              "spark.sql.shuffle.partitions"));

  public static boolean canCaptureParameter(String parameterName) {
    return allowedParams.contains(parameterName);
  }
}
