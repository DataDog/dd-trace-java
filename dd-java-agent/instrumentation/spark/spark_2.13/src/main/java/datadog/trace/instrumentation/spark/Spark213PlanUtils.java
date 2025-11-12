package datadog.trace.instrumentation.spark;

import java.lang.invoke.MethodHandle;
import org.apache.spark.sql.execution.SparkPlanInfo;
import scala.Option;
import scala.collection.immutable.Map;

public class Spark213PlanUtils extends AbstractSparkPlanUtils {
  private static final MethodHandle constructor =
      methodLoader.constructor(
          SparkPlanInfo.class,
          String.class,
          String.class,
          scala.collection.immutable.Seq.class,
          scala.collection.immutable.Map.class,
          scala.collection.immutable.Seq.class);
  private static final MethodHandle databricksConstructor =
      methodLoader.constructor(
          SparkPlanInfo.class,
          String.class,
          String.class,
          scala.collection.immutable.Seq.class,
          scala.collection.immutable.Map.class,
          scala.collection.immutable.Seq.class,
          Option.class,
          String.class,
          Option.class);

  @Override
  protected MethodHandle getConstructor() {
    return constructor;
  }

  @Override
  protected MethodHandle getDatabricksConstructor() {
    return databricksConstructor;
  }

  @Override
  protected Object[] getStandardArgs(SparkPlanInfo planInfo, Map meta) {
    return new Object[] {
      planInfo.nodeName(), planInfo.simpleString(), planInfo.children(), meta, planInfo.metrics()
    };
  }
}
