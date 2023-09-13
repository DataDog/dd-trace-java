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

  /**
   * Application parameters defined at the start of the application, with the spark-submit command
   */
  private static final Set<String> allowedApplicationParams =
      new HashSet<>(
          Arrays.asList(
              "spark.default.parallelism",
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
              "spark.executor.instances",
              "spark.executor.memory",
              "spark.executor.pyspark.memory",
              "spark.executor.memoryOverhead",
              "spark.executor.memoryOverheadFactor",
              "spark.files.maxPartitionBytes",
              "spark.master",
              "spark.memory.fraction",
              "spark.memory.storageFraction",
              "spark.memory.offHeap.enabled",
              "spark.memory.offHeap.size",
              "spark.submit.deployMode",
              "spark.sql.autoBroadcastJoinThreshold",
              "spark.sql.files.maxPartitionBytes",
              "spark.sql.shuffle.partitions"));

  /**
   * Job-specific parameters that can be used to control job execution or provide metadata about the
   * job being executed
   */
  private static final Set<String> allowedJobParams =
      new HashSet<>(
          Arrays.asList(
              "spark.app.id",
              "spark.app.name",
              "spark.app.startTime",
              "spark.databricks.clusterSource",
              "spark.databricks.clusterUsageTags.clusterId",
              "spark.databricks.clusterUsageTags.clusterName",
              "spark.databricks.clusterUsageTags.clusterNodeType",
              "spark.databricks.clusterUsageTags.clusterWorkers",
              "spark.databricks.clusterUsageTags.driverContainerId",
              "spark.databricks.clusterUsageTags.sparkVersion",
              "spark.databricks.clusterUsageTags.workerEnvironmentId",
              "spark.databricks.env",
              "spark.databricks.job.type",
              "spark.databricks.sparkContextId",
              "spark.job.description",
              "spark.jobGroup.id",
              "spark.sql.execution.id",
              "sql.streaming.queryId",
              "streaming.sql.batchId",
              "user"));

  public static boolean canCaptureApplicationParameter(String parameterName) {
    return allowedApplicationParams.contains(parameterName)
        || allowedJobParams.contains(parameterName);
  }

  public static boolean canCaptureJobParameter(String parameterName) {
    return allowedJobParams.contains(parameterName);
  }
}
