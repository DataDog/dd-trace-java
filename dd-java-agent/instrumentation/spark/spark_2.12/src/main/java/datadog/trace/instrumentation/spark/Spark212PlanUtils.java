package datadog.trace.instrumentation.spark;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.apache.spark.sql.execution.SparkPlan;
import org.apache.spark.sql.execution.exchange.ReusedExchangeExec;
import scala.collection.JavaConverters;

// An extension of how Spark translates `SparkPlan`s to `SparkPlanInfo`, see here:
// https://github.com/apache/spark/blob/v3.5.0/sql/core/src/main/scala/org/apache/spark/sql/execution/SparkPlanInfo.scala#L54
public class Spark212PlanUtils {
  public static ArrayList<SparkPlan> extractChildren(SparkPlan plan) {
    /*
    Get children of this node. Logic in Spark:

    val children = plan match {
      case ReusedExchangeExec(_, child) => child :: Nil
      case _ => plan.children ++ plan.subqueries
    }
     */
    ArrayList<SparkPlan> children = new ArrayList<>();
    if (plan instanceof ReusedExchangeExec) {
      children.add(((ReusedExchangeExec) plan).child());
    }

    for (Iterator<SparkPlan> it = JavaConverters.asJavaIterator(plan.subqueries().iterator());
        it.hasNext(); ) {
      children.add(it.next());
    }
    for (Iterator<SparkPlan> it = JavaConverters.asJavaIterator(plan.children().iterator());
        it.hasNext(); ) {
      children.add(it.next());
    }

    return children;
  }

  public static Map<String, String> extractPlanProduct(SparkPlan plan) {
    HashMap<String, String> args = new HashMap<>();
    HashMap<String, String> unparsed = new HashMap<>();

    int i = 0;
    for (Iterator<Object> it = JavaConverters.asJavaIterator(plan.productIterator());
        it.hasNext(); ) {
      Object obj = it.next();
      String key = String.format("_dd.unknown_key.%d", i);

      String val = CommonSparkPlanUtils.parsePlanProduct(obj);
      if (val != null) {
        args.put(key, val);
      } else {
        unparsed.put(key, obj.getClass().getName());
      }

      i++;
    }

    if (unparsed.size() > 0) {
      // For now, place what we can't parse here with the types so we're aware of them
      args.put("_dd.unparsed", unparsed.toString());
    }
    return args;
  }
}
