package datadog.trace.instrumentation.spark;

import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.spark.sql.execution.SparkPlanInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.immutable.Map;

abstract class AbstractSparkPlanUtils {
  private static final Logger log = LoggerFactory.getLogger(AbstractSparkPlanUtils.class);

  protected static final MethodHandles methodLoader =
      new MethodHandles(ClassLoader.getSystemClassLoader());

  protected abstract MethodHandle getConstructor();

  protected abstract MethodHandle getDatabricksConstructor();

  // Deals with Seq which changed from Scala 2.12 to 2.13, so delegate to version-specific classes
  protected abstract Object[] getStandardArgs(SparkPlanInfo planInfo, Map meta);

  // Attempt to create a new SparkPlanInfo with additional metadata replaced
  // Since the fields are immutable we must instantiate a new SparkPlanInfo to do this
  public SparkPlanInfo upsertSparkPlanInfoMetadata(
      SparkPlanInfo planInfo, scala.collection.immutable.Map<String, String> meta) {
    if (getDatabricksConstructor() != null) {
      List<Object> databricksArgs = new ArrayList<>(Arrays.asList(getStandardArgs(planInfo, meta)));
      try {
        databricksArgs.add(SparkPlanInfo.class.getMethod("estRowCount").invoke(planInfo));
        databricksArgs.add(SparkPlanInfo.class.getMethod("rddScopeId").invoke(planInfo));
        databricksArgs.add(SparkPlanInfo.class.getMethod("explainId").invoke(planInfo));
      } catch (Throwable t) {
        log.warn("Error obtaining Databricks-specific SparkPlanInfo args", t);
      }

      SparkPlanInfo newPlan =
          methodLoader.invoke(getDatabricksConstructor(), databricksArgs.toArray());
      if (newPlan != null) {
        return newPlan;
      }
    }

    if (getConstructor() != null) {
      SparkPlanInfo newPlan =
          methodLoader.invoke(getConstructor(), getStandardArgs(planInfo, meta));
      if (newPlan != null) {
        return newPlan;
      }
    }

    return null;
  }
}
