package datadog.trace.instrumentation.spark;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.spark.SparkConf;
import scala.Tuple2;

/**
 * Helper class to only capture a selected subset of the spark configuration
 *
 * @see <a href="https://spark.apache.org/docs/latest/configuration.html">Spark Configuration</a>
 */
class SparkConfAllowList {
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
              "spark.databricks.job.parentRunId",
              "spark.databricks.job.type",
              "spark.databricks.sparkContextId",
              "spark.databricks.workload.name",
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
              "spark.job.description",
              "spark.jobGroup.id",
              "spark.master",
              "spark.memory.fraction",
              "spark.memory.storageFraction",
              "spark.memory.offHeap.enabled",
              "spark.memory.offHeap.size",
              "spark.submit.deployMode",
              "spark.sql.autoBroadcastJoinThreshold",
              "spark.sql.files.maxPartitionBytes",
              "spark.sql.shuffle.partitions",
              "spark.sql.execution.id",
              "sql.streaming.queryId",
              "streaming.sql.batchId",
              "user"));

  public static boolean canCaptureJobParameter(String parameterName) {
    return allowedJobParams.contains(parameterName);
  }

  public static List<Map.Entry<String, String>> getRedactedSparkConf(SparkConf conf) {
    // Using values from
    // https://github.com/apache/spark/blob/v3.5.1/core/src/main/scala/org/apache/spark/internal/config/package.scala#L1150-L1158
    String redactionPattern =
        conf.get("spark.redaction.regex", "(?i)secret|password|token|access.key|api.key");
    List<Map.Entry<String, String>> redacted = new ArrayList<>();
    Pattern pattern = Pattern.compile(redactionPattern);

    for (Tuple2<String, String> entry : conf.getAll()) {
      String key = entry._1;
      String value = entry._2;

      if (pattern.matcher(key).find() || pattern.matcher(value).find()) {
        redacted.add(new AbstractMap.SimpleEntry<>(key, "[redacted]"));
      } else {
        redacted.add(new AbstractMap.SimpleEntry<>(key, value));
      }
    }

    return redacted;
  }
}
