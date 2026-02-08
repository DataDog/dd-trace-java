package datadog.trace.instrumentation.spark;

import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.spark.SparkConf;
import org.apache.spark.executor.TaskMetrics;
import org.apache.spark.scheduler.SparkListenerJobStart;
import org.apache.spark.scheduler.StageInfo;
import org.apache.spark.sql.execution.SparkPlanInfo;
import org.apache.spark.sql.execution.metric.SQLMetricInfo;
import org.apache.spark.util.AccumulatorV2;
import scala.Function1;
import scala.collection.JavaConverters;
import scala.collection.mutable.ArrayBuffer;

/**
 * DatadogSparkListener compiled for Scala 2.12
 *
 * <p>The signature of scala.Seq change between 2.12 and 2.13. Methods using scala.Seq needs to be
 * compiled with the specific scala version
 */
public class DatadogSpark212Listener extends AbstractDatadogSparkListener {
  private static final MethodHandles methodLoader =
      new MethodHandles(ClassLoader.getSystemClassLoader());
  private static final MethodHandle externalAccums =
      methodLoader.method(TaskMetrics.class, "externalAccums");
  private static final MethodHandle withExternalAccums =
      methodLoader.method(TaskMetrics.class, "withExternalAccums", new Class[] {});

  public DatadogSpark212Listener(SparkConf sparkConf, String appId, String sparkVersion) {
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

  @Override
  protected Collection<SparkPlanInfo> getPlanInfoChildren(SparkPlanInfo info) {
    return JavaConverters.asJavaCollection(info.children());
  }

  @Override
  protected List<SQLMetricInfo> getPlanInfoMetrics(SparkPlanInfo info) {
    return JavaConverters.seqAsJavaList(info.metrics());
  }

  @Override
  protected int[] getStageParentIds(StageInfo info) {
    int[] parentIds = new int[info.parentIds().length()];
    for (int i = 0; i < parentIds.length; i++) {
      parentIds[i] = (int) info.parentIds().apply(i);
    }

    return parentIds;
  }

  @Override
  protected List<AccumulatorV2> getExternalAccumulators(TaskMetrics metrics) {
    if (metrics == null) {
      return null;
    }

    Function1 lambda =
        (Function1<ArrayBuffer<AccumulatorV2>, List<AccumulatorV2>>)
            accumulators -> JavaConverters.seqAsJavaList(accumulators);
    List<AccumulatorV2> res = methodLoader.invoke(withExternalAccums, metrics, lambda);
    if (res != null) {
      return res;
    }

    // withExternalAccums didn't work, try the legacy method
    ArrayBuffer<AccumulatorV2> accumulators = methodLoader.invoke(externalAccums, metrics);
    if (accumulators != null && !accumulators.isEmpty()) {
      return JavaConverters.seqAsJavaList(accumulators);
    }

    return null;
  }
}
