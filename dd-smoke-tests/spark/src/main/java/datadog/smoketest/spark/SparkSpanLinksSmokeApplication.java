package datadog.smoketest.spark;

import org.apache.spark.sql.SparkSession;

/** Small batch application used to validate serialized Spark trace topology. */
public final class SparkSpanLinksSmokeApplication {
  private SparkSpanLinksSmokeApplication() {}

  public static void main(String[] args) {
    SparkSession spark =
        SparkSession.builder()
            .master("local[2]")
            .appName("spark-span-links-smoke")
            .config("spark.default.parallelism", "2")
            .config("spark.sql.shuffle.partitions", "2")
            .config("spark.ui.enabled", "false")
            .config("spark.openlineage.parentRunId", "ad3b6baa-8d88-3b38-8dbe-f06232249a84")
            .config("spark.openlineage.parentJobNamespace", "default")
            .config("spark.openlineage.parentJobName", "parent-job")
            .config("spark.openlineage.rootParentRunId", "01964820-5280-7674-b04e-82fbed085f39")
            .config("spark.openlineage.rootParentJobNamespace", "default")
            .config("spark.openlineage.rootParentJobName", "root-job")
            .getOrCreate();
    try {
      spark.range(0, 10).coalesce(1).count();
    } finally {
      spark.stop();
    }
  }
}
