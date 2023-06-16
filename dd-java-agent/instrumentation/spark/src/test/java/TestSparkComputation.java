import org.apache.spark.sql.SparkSession;

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

  public static String getSparkVersion() {
    return org.apache.spark.package$.MODULE$.SPARK_VERSION();
  }
}
