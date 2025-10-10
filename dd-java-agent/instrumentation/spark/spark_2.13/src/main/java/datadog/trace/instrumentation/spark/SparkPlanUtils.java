package datadog.trace.instrumentation.spark;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.apache.spark.sql.catalyst.trees.TreeNode;
import org.apache.spark.sql.execution.ReusedSubqueryExec;
import org.apache.spark.sql.execution.SparkPlan;
import org.apache.spark.sql.execution.adaptive.AdaptiveSparkPlanExec;
import org.apache.spark.sql.execution.adaptive.QueryStageExec;
import org.apache.spark.sql.execution.columnar.InMemoryTableScanExec;
import org.apache.spark.sql.execution.exchange.ReusedExchangeExec;
import scala.None$;
import scala.collection.JavaConverters;
import scala.collection.immutable.$colon$colon;
import scala.collection.immutable.Iterable;
import scala.collection.immutable.Nil$;

// An extension of how Spark translates `SparkPlan`s to `SparkPlanInfo`, see here:
// https://github.com/apache/spark/blob/v3.5.0/sql/core/src/main/scala/org/apache/spark/sql/execution/SparkPlanInfo.scala#L54
public class SparkPlanUtils {
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

      // TODO: improve parsing of certain types
      //  1. Some() should be unwrapped
      //  2. requiredSchema on Scan * (currently showing StructType)

      // TODO: support a few more common types?
      // condition=org.apache.spark.sql.catalyst.expressions.objects.Invoke
      // joinType=org.apache.spark.sql.catalyst.plans.Inner$
      // buildSide=org.apache.spark.sql.catalyst.optimizer.BuildRight$
      // shuffleOrigin=org.apache.spark.sql.execution.exchange.ENSURE_REQUIREMENTS$
      // outputPartitioning=org.apache.spark.sql.catalyst.plans.physical.SinglePartition$
      if (obj instanceof String
          || obj instanceof Boolean
          || obj instanceof Collection
          || obj instanceof None$
          || obj instanceof Integer) {
        args.put(plan.productElementName(i), obj.toString());
      } else if (obj instanceof $colon$colon || obj instanceof Nil$) {
        args.put(plan.productElementName(i), JavaConverters.asJava(((Iterable) obj)).toString());
      } else if (obj instanceof TreeNode) {
        // Filter out any potential child nodes
        // TODO: Exempt conditions from this branch
        // e.g. condition=class org.apache.spark.sql.catalyst.expressions.objects.Invoke
        unparsed.put(plan.productElementName(i), obj.getClass().getName());
      } else {
        args.put(plan.productElementName(i), obj.toString());
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
