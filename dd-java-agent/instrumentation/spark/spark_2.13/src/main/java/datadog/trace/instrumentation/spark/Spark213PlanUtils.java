package datadog.trace.instrumentation.spark;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.apache.spark.sql.execution.ReusedSubqueryExec;
import org.apache.spark.sql.execution.SparkPlan;
import org.apache.spark.sql.execution.adaptive.AdaptiveSparkPlanExec;
import org.apache.spark.sql.execution.adaptive.QueryStageExec;
import org.apache.spark.sql.execution.columnar.InMemoryTableScanExec;
import org.apache.spark.sql.execution.exchange.ReusedExchangeExec;
import scala.collection.JavaConverters;

// An extension of how Spark translates `SparkPlan`s to `SparkPlanInfo`, see here:
// https://github.com/apache/spark/blob/v3.5.0/sql/core/src/main/scala/org/apache/spark/sql/execution/SparkPlanInfo.scala#L54
public class Spark213PlanUtils {
  public static ArrayList<SparkPlan> extractChildren(SparkPlan plan) {
    /*
    Get children of this node. Logic in Spark:

    val children = plan match {
      case ReusedExchangeExec(_, child) => child :: Nil
      case ReusedSubqueryExec(child) => child :: Nil
      case a: AdaptiveSparkPlanExec => a.executedPlan :: Nil
      case stage: QueryStageExec => stage.plan :: Nil
      case inMemTab: InMemoryTableScanExec => inMemTab.relation.cachedPlan :: Nil
      case EmptyRelationExec(logical) => (logical :: Nil)
      case _ => plan.children ++ plan.subqueries
    }
     */
    // TODO: How does this interact with different versions of Spark? (specifically an older version
    // that does not have those types)
    ArrayList<SparkPlan> children = new ArrayList<>();
    if (plan instanceof ReusedExchangeExec) {
      children.add(((ReusedExchangeExec) plan).child());
    } else if (plan instanceof ReusedSubqueryExec) {
      children.add(((ReusedSubqueryExec) plan).child());
    } else if (plan instanceof AdaptiveSparkPlanExec) {
      children.add(((AdaptiveSparkPlanExec) plan).executedPlan());
    } else if (plan instanceof QueryStageExec) {
      children.add(((QueryStageExec) plan).plan());
    } else if (plan instanceof InMemoryTableScanExec) {
      children.add(((InMemoryTableScanExec) plan).relation().cachedPlan());
      //  New as of Spark 4.0.0
      //  } else if (plan instanceof EmptyRelationExec) {
      //    children.add(((EmptyRelationExec) plan).logical);
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
      String key = plan.productElementName(i);

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
