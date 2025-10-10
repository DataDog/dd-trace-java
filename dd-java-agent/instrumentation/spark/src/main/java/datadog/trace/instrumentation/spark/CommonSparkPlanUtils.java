package datadog.trace.instrumentation.spark;

import java.util.Collection;
import org.apache.spark.sql.catalyst.trees.TreeNode;
import scala.None$;
import scala.collection.JavaConverters;
import scala.collection.immutable.$colon$colon;
import scala.collection.immutable.Iterable;
import scala.collection.immutable.Nil$;

public class CommonSparkPlanUtils {
  public static String parsePlanProduct(Object value) {
    // TODO: improve parsing of certain types
    //  1. Some() should be unwrapped
    //  2. requiredSchema on Scan * (currently showing StructType)

    // TODO: support a few more common types?
    // condition=org.apache.spark.sql.catalyst.expressions.objects.Invoke
    // joinType=org.apache.spark.sql.catalyst.plans.Inner$
    // buildSide=org.apache.spark.sql.catalyst.optimizer.BuildRight$
    // shuffleOrigin=org.apache.spark.sql.execution.exchange.ENSURE_REQUIREMENTS$
    // outputPartitioning=org.apache.spark.sql.catalyst.plans.physical.SinglePartition$
    if (value instanceof String
        || value instanceof Boolean
        || value instanceof Collection
        || value instanceof None$
        || value instanceof Integer) {
      return value.toString();
    } else if (value instanceof $colon$colon || value instanceof Nil$) {
      return JavaConverters.asJavaIterable(((Iterable) value)).toString();
    } else if (value instanceof TreeNode) {
      // Filter out any potential child nodes
      // TODO: Exempt conditions from this branch
      // e.g. condition=class org.apache.spark.sql.catalyst.expressions.objects.Invoke
      return null;
    } else {
      return value.toString();
    }
  }
}
