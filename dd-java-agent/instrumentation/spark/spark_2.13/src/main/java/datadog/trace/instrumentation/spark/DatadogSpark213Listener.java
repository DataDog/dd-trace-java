package datadog.trace.instrumentation.spark;

import java.util.ArrayList;
import org.apache.spark.SparkConf;
import org.apache.spark.scheduler.SparkListenerJobStart;

/**
 * DatadogSparkListener compiled for Scala 2.13
 *
 * <p>The signature of scala.Seq change between 2.12 and 2.13. Methods using scala.Seq needs to be
 * compiled with the specific scala version
 */
public class DatadogSpark213Listener extends AbstractDatadogSparkListener {
  public DatadogSpark213Listener(SparkConf sparkConf, String appId, String sparkVersion) {
    super(sparkConf, appId, sparkVersion);
  }

  @Override
  protected ArrayList<Integer> getSparkJobStageIds(SparkListenerJobStart jobStart) {
    ArrayList<Integer> javaIds = new ArrayList<>(jobStart.stageInfos().length());
    jobStart.stageInfos().foreach(stage -> javaIds.add(stage.stageId()));
    return javaIds;
  }

  @Override
  protected String getSparkJobName(SparkListenerJobStart jobStart) {
    if (jobStart.stageInfos().nonEmpty()) {
      // In the spark UI, the name of a job is the name of its last stage
      return jobStart.stageInfos().last().name();
    }

    return null;
  }

  @Override
  protected int getStageCount(SparkListenerJobStart jobStart) {
    return jobStart.stageInfos().length();
  }
}
