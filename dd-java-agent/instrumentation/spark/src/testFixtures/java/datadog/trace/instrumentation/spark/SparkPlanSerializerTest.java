package datadog.trace.instrumentation.spark;

import org.apache.spark.sql.catalyst.trees.TreeNode;

public class SparkPlanSerializerTest extends AbstractSparkPlanSerializer {

  @Override
  public String getKey(int idx, TreeNode node) {
    return String.valueOf(idx);
  }
}
