package datadog.trace.instrumentation.spark;

import org.apache.spark.sql.catalyst.trees.TreeNode;

public class Spark212PlanSerializer extends AbstractSparkPlanSerializer {
  public String getKey(int idx, TreeNode node) {
    return String.format("_dd.unknown_key.%d", idx);
  }
}
