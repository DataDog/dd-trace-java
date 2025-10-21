package datadog.trace.instrumentation.spark;

import org.apache.spark.sql.catalyst.trees.TreeNode;

public class Spark213PlanSerializer extends AbstractSparkPlanSerializer {
  public String getKey(int idx, TreeNode node) {
    return node.productElementName(idx);
  }
}
