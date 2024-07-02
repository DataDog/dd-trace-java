package datadog.trace.instrumentation.spark;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.spark.SparkConf;
import org.apache.spark.scheduler.SparkListenerJobStart;
import org.apache.spark.scheduler.StageInfo;
import org.apache.spark.sql.execution.SparkPlanInfo;
import org.apache.spark.sql.execution.metric.SQLMetricInfo;
import scala.collection.JavaConverters;

/**
 * DatadogSparkListener compiled for Scala 2.11
 *
 * <p>The signature of scala.Seq change between 2.12 and 2.13. Methods using scala.Seq needs to be
 * compiled with the specific scala version
 */
public class DatadogSpark211Listener extends AbstractDatadogSparkListener {
  public DatadogSpark211Listener(SparkConf sparkConf, String appId, String sparkVersion) {
    super(sparkConf, appId, sparkVersion);
  }

  @Override
  protected ArrayList<Integer> getSparkJobStageIds(SparkListenerJobStart jobStart) {
    ArrayList<Integer> javaIds = new ArrayList<>(jobStart.stageInfos().length());
    JavaConverters.seqAsJavaListConverter(jobStart.stageInfos()).asJava().forEach(stage -> javaIds.add(stage.stageId()));
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

  @Override
  protected Collection<SparkPlanInfo> getPlanInfoChildren(SparkPlanInfo info) {
    return JavaConverters.asJavaCollectionConverter(info.children()).asJavaCollection();
  }

  @Override
  protected List<SQLMetricInfo> getPlanInfoMetrics(SparkPlanInfo info) {
    return JavaConverters.seqAsJavaListConverter(info.metrics()).asJava();
  }

  @Override
  protected int[] getStageParentIds(StageInfo info) {
    int[] parentIds = new int[info.parentIds().length()];
    for (int i = 0; i < parentIds.length; i++) {
      parentIds[i] = (int) info.parentIds().apply(i);
    }

    return parentIds;
  }
}
