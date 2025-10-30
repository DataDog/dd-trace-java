package datadog.trace.instrumentation.spark;

import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.List;
import org.apache.spark.sql.execution.SparkPlanInfo;
import scala.Option;
import scala.collection.Seq;

public class SparkPlanInfoUtils {
  private final MethodHandles methodLoader = new MethodHandles(ClassLoader.getSystemClassLoader());

  private final MethodHandle[] constructors =
      new MethodHandle[] {
        methodLoader.constructor(
            SparkPlanInfo.class,
            String.class,
            String.class,
            scala.collection.immutable.Seq.class,
            scala.collection.immutable.Map.class,
            scala.collection.immutable.Seq.class),
        methodLoader.constructor(
            SparkPlanInfo.class,
            String.class,
            String.class,
            Seq.class,
            scala.collection.immutable.Map.class,
            Seq.class),
      };
  private final MethodHandle databricksConstructor =
      methodLoader.constructor(
          SparkPlanInfo.class,
          String.class,
          String.class,
          Seq.class,
          scala.collection.immutable.Map.class,
          Seq.class,
          Option.class,
          String.class,
          Option.class);

  public SparkPlanInfo upsertSparkPlanInfoMetadata(
      SparkPlanInfo planInfo, scala.collection.immutable.Map<String, String> meta) {
    // Attempt to create a new SparkPlanInfo with additional metadata replaced
    // Since the fields are immutable we must instantiate a new SparkPlanInfo to do this

    Object[] standardArgs;
    try {
      standardArgs =
          new Object[] {
            SparkPlanInfo.class.getMethod("nodeName").invoke(planInfo),
            SparkPlanInfo.class.getMethod("simpleString").invoke(planInfo),
            SparkPlanInfo.class.getMethod("children").invoke(planInfo),
            meta,
            SparkPlanInfo.class.getMethod("metrics").invoke(planInfo)
          };
    } catch (Throwable t) {
      return null;
    }

    if (databricksConstructor != null) {
      List<Object> databricksArgs = Arrays.asList(standardArgs);
      try {
        databricksArgs.add(SparkPlanInfo.class.getMethod("estRowCount").invoke(planInfo));
        databricksArgs.add(SparkPlanInfo.class.getMethod("rddScopeId").invoke(planInfo));
        databricksArgs.add(SparkPlanInfo.class.getMethod("explainId").invoke(planInfo));
      } catch (Throwable ignored) {
      }

      SparkPlanInfo newPlan = methodLoader.invoke(databricksConstructor, databricksArgs.toArray());
      if (newPlan != null) {
        return newPlan;
      }
    }

    for (MethodHandle constructor : constructors) {
      if (constructor != null) {
        SparkPlanInfo newPlan = methodLoader.invoke(constructor, standardArgs);
        if (newPlan != null) {
          return newPlan;
        }
      }
    }

    return null;
  }
}
