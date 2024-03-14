package datadog.trace.instrumentation.spark;

import java.util.concurrent.TimeoutException;
import org.apache.spark.api.java.function.MapFunction;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.streaming.StreamingQuery;

/**
 * Helper class to generate the spark computation for test. Doing it in java because spark does not
 * have a good support for groovy
 */
public class TestSparkComputation {
  public static void generateTestSparkComputation(SparkSession sparkSession) {
    // This will generate 1 spark job that has two stages
    // - the first because distinct will need a shuffle operation
    // - the second one triggered by the count() action
    sparkSession.sparkContext().range(1, 10, 1, 2).distinct().count();
  }

  public static void generateTestFailingSparkComputation(SparkSession sparkSession) {
    sparkSession
        .sparkContext()
        .range(1, 10, 1, 2)
        .toJavaRDD()
        .map(x -> ((Long) null).toString())
        .collect();
  }

  public static StreamingQuery generateTestFailingStreamingComputation(Dataset<String> ds)
      throws TimeoutException {
    return ds.map((MapFunction<String, String>) x -> ((Long) null).toString(), Encoders.STRING())
        .writeStream()
        .queryName("failing-query")
        .outputMode("append")
        .format("console")
        .start();
  }

  public static StreamingQuery generateTestStreamingComputation(Dataset<String> dataSet) {
    return dataSet
        .selectExpr("value", "current_timestamp() as event_time")
        .withWatermark("event_time", "0 seconds")
        .groupBy("value")
        .count()
        .writeStream()
        .queryName("test-query")
        .outputMode("complete")
        .format("console")
        .start();
  }

  static class IdentityMapFunction implements MapFunction<String, String> {
    @Override
    public String call(String s) {
      return s;
    }
  }

  public static Dataset<String> applyIdentityMapFunction(Dataset<String> ds) {
    return ds.map(new IdentityMapFunction(), Encoders.STRING());
  }

  public static String getSparkVersion() {
    return org.apache.spark.package$.MODULE$.SPARK_VERSION();
  }

  public static SparkSession createSparkSession(String appName) {
    return SparkSession.builder()
        .appName(appName)
        .config("spark.master", "local[2]")
        .config("spark.sql.shuffle.partitions", "2")
        .getOrCreate();
  }

  public static SparkSession createDatabricksWorkflowsSparkSession(String appName) {
    return SparkSession.builder()
        .appName(appName)
        .config("spark.master", "local[2]")
        .config("spark.sql.shuffle.partitions", "2")
        .config("spark.databricks.sparkContextId", "3291395623902517763")
        .config("spark.databricks.job.id", "3822225623902514353")
        .config("spark.databricks.job.parentRunId", "3851395623902519743")
        .config("spark.databricks.job.runId", "3851395623902519743")
        .getOrCreate();
  }
}
