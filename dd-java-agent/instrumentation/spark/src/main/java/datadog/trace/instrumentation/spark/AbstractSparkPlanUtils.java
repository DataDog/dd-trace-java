package datadog.trace.instrumentation.spark;

import java.util.ArrayList;
import java.util.Map;
import org.apache.spark.sql.catalyst.plans.QueryPlan;
import org.apache.spark.sql.catalyst.trees.TreeNode;
import scala.Option;
import scala.collection.Iterable;

public abstract class AbstractSparkPlanUtils {
  public abstract Map<String, String> extractPlanProduct(TreeNode node);

  public String parsePlanProduct(Object value) {
    if (value == null) {
      return "null";
    } else if (value instanceof Iterable) {
      ArrayList<String> list = new ArrayList<>();
      ((Iterable) value).foreach(item -> list.add(parsePlanProduct(item)));
      return "[\"" + String.join("\", \"", list) + "\"]";
    } else if (value instanceof Option) {
      return parsePlanProduct(((Option) value).getOrElse(() -> "none"));
    } else if (value instanceof QueryPlan) { // Filter out values referencing child nodes
      return null;
    } else {
      return value.toString();
    }
  }
}
