package datadog.trace.instrumentation.spark;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.apache.spark.sql.execution.SparkPlan;
import scala.collection.JavaConverters;

// An extension of how Spark translates `SparkPlan`s to `SparkPlanInfo`, see here:
// https://github.com/apache/spark/blob/v3.5.0/sql/core/src/main/scala/org/apache/spark/sql/execution/SparkPlanInfo.scala#L54
public class Spark213PlanUtils {
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
