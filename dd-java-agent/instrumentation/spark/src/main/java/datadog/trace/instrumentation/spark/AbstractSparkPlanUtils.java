package datadog.trace.instrumentation.spark;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.apache.spark.sql.catalyst.plans.QueryPlan;
import org.apache.spark.sql.catalyst.trees.TreeNode;
import scala.Option;
import scala.collection.Iterable;
import scala.collection.JavaConverters;

// An extension of how Spark translates `SparkPlan`s to `SparkPlanInfo`, see here:
// https://github.com/apache/spark/blob/v3.5.0/sql/core/src/main/scala/org/apache/spark/sql/execution/SparkPlanInfo.scala#L54
public abstract class AbstractSparkPlanUtils {
  private final int MAX_DEPTH = 4;
  private final ObjectMapper mapper =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  public abstract String getKey(int idx, TreeNode node);

  public Map<String, String> extractFormattedProduct(TreeNode plan) {
    HashMap<String, String> result = new HashMap<>();
    extractPlanProduct(plan, 0)
        .forEach(
            (key, value) -> {
              result.put(key, writeObjectToString(value));
            });
    return result;
  }

  private Map<String, Object> extractPlanProduct(TreeNode node, int depth) {
    HashMap<String, Object> args = new HashMap<>();
    HashMap<String, String> unparsed = new HashMap<>();

    int i = 0;
    for (Iterator<Object> it = JavaConverters.asJavaIterator(node.productIterator());
        it.hasNext(); ) {
      Object obj = it.next();

      Object val = parsePlanProduct(obj, depth);
      if (val != null) {
        args.put(getKey(i, node), val);
      } else {
        unparsed.put(getKey(i, node), obj.getClass().getName());
      }

      i++;
    }

    if (unparsed.size() > 0) {
      // For now, place what we can't parse here with the types so we're aware of them
      args.put("_dd.unparsed", unparsed);
    }
    return args;
  }

  // Should only call on final values being written to `meta`
  private String writeObjectToString(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (IOException e) {
      return null;
    }
  }

  // Should really only return valid JSON types (Array, Map, String, Boolean, Number, null)
  private Object parsePlanProduct(Object value, int depth) {
    if (value == null) {
      return "null";
    } else if (value instanceof Boolean || Number.class.isInstance(value)) {
      return value;
    } else if (value instanceof Option) {
      return parsePlanProduct(((Option) value).getOrElse(() -> "none"), depth);
    } else if (value instanceof QueryPlan) {
      // Filter out values referencing child nodes
      return null;
    } else if (value instanceof Iterable && depth <= MAX_DEPTH) {
      ArrayList<Object> list = new ArrayList<>();
      ((Iterable) value).foreach(item -> list.add(parsePlanProduct(item, depth + 1)));
      return list;
    } else {
      return value.toString();
    }
  }
}
