package datadog.trace.instrumentation.spark;

import org.apache.spark.sql.catalyst.trees.TreeNode;

public class Spark212PlanSerializer extends AbstractSparkPlanSerializer {
  @Override
  public String getKey(int idx, TreeNode node) {
    return "_dd.unknown_key." + idx;
  }
}
