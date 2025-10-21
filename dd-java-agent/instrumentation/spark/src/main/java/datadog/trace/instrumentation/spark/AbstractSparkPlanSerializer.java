package datadog.trace.instrumentation.spark;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.apache.spark.Partitioner;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.plans.JoinType;
import org.apache.spark.sql.catalyst.plans.QueryPlan;
import org.apache.spark.sql.catalyst.plans.physical.BroadcastMode;
import org.apache.spark.sql.catalyst.plans.physical.Partitioning;
import org.apache.spark.sql.catalyst.trees.TreeNode;
import scala.Option;
import scala.collection.Iterable;
import scala.collection.JavaConverters;

// An extension of how Spark translates `SparkPlan`s to `SparkPlanInfo`, see here:
// https://github.com/apache/spark/blob/v3.5.0/sql/core/src/main/scala/org/apache/spark/sql/execution/SparkPlanInfo.scala#L54
public abstract class AbstractSparkPlanSerializer {
  private final int MAX_DEPTH = 4;
  private final int MAX_LENGTH = 50;
  private final ObjectMapper mapper = AbstractDatadogSparkListener.objectMapper;

  private final Class[] SAFE_CLASSES = {
    Attribute.class, // simpleString appends data type, avoid by using toString
    JoinType.class, // enum
    Partitioner.class, // not a product or TreeNode
    BroadcastMode.class, // not a product or TreeNode
    maybeGetClass("org.apache.spark.sql.execution.exchange.ShuffleOrigin"), // enum (v3+)
    maybeGetClass("org.apache.spark.sql.catalyst.optimizer.BuildSide"), // enum (v3+)
    maybeGetClass(
        "org.apache.spark.sql.execution.ShufflePartitionSpec"), // not a product or TreeNode (v3+)
  };

  public abstract String getKey(int idx, TreeNode node);

  public Map<String, String> extractFormattedProduct(TreeNode plan) {
    HashMap<String, String> result = new HashMap<>();
    safeParseTreeNode(plan, 0)
        .forEach(
            (key, value) -> {
              result.put(key, writeObjectToString(value));
            });
    return result;
  }

  protected Map<String, Object> safeParseTreeNode(TreeNode node, int depth) {
    HashMap<String, Object> args = new HashMap<>();
    HashMap<String, String> unparsed = new HashMap<>();

    int i = 0;
    for (Iterator<Object> it = JavaConverters.asJavaIterator(node.productIterator());
        it.hasNext(); ) {
      Object obj = it.next();

      Object val = safeParseObjectToJson(obj, depth);
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
  protected String writeObjectToString(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (IOException e) {
      return null;
    }
  }

  protected Object safeParseObjectToJson(Object value, int depth) {
    // This function MUST not arbitrarily serialize the object as we can't be sure what it is.
    // A null return indicates object is unserializable, otherwise it should really only return
    // valid JSON types (Array, Map, String, Boolean, Number, null)

    if (value == null) {
      return "null";
    } else if (value instanceof String
        || value instanceof Boolean
        || Number.class.isInstance(value)) {
      return value;
    } else if (value instanceof Option) {
      return safeParseObjectToJson(((Option) value).getOrElse(() -> "none"), depth);
    } else if (value instanceof QueryPlan) {
      // don't duplicate child nodes
      return null;
    } else if (value instanceof Iterable && depth < MAX_DEPTH) {
      ArrayList<Object> list = new ArrayList<>();
      for (Object item : JavaConverters.asJavaIterable((Iterable) value)) {
        Object res = safeParseObjectToJson(item, depth + 1);
        if (list.size() < MAX_LENGTH && res != null) {
          list.add(res);
        }
      }
      return list;
    } else if (value instanceof Partitioning) {
      if (value instanceof TreeNode && depth + 1 < MAX_DEPTH) {
        HashMap<String, Object> inner = new HashMap<>();
        inner.put(
            value.getClass().getSimpleName(), safeParseTreeNode(((TreeNode) value), depth + 2));
        return inner;
      } else {
        return value.toString();
      }
    } else if (instanceOf(value, SAFE_CLASSES)) {
      return value.toString();
    } else if (value instanceof TreeNode) {
      // fallback case, leave at bottom
      return getSimpleString((TreeNode) value);
    }

    return null;
  }

  private String getSimpleString(TreeNode value) {
    try {
      // in Spark v3+, the signature of `simpleString` includes an int parameter for `maxFields`
      return TreeNode.class
          .getDeclaredMethod("simpleString", new Class[] {int.class})
          .invoke(value, MAX_LENGTH)
          .toString();
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
      try {
        // Attempt the Spark v2 `simpleString` signature
        return TreeNode.class.getDeclaredMethod("simpleString").invoke(value).toString();
      } catch (NoSuchMethodException
          | IllegalAccessException
          | InvocationTargetException innerException) {
      }

      return null;
    }
  }

  // Use reflection rather than native `instanceof` for classes added in later Spark versions
  private boolean instanceOf(Object value, Class[] classes) {
    for (Class cls : classes) {
      if (cls != null && cls.isInstance(value)) {
        return true;
      }
    }

    return false;
  }

  private Class maybeGetClass(String cls) {
    try {
      return Class.forName(cls);
    } catch (ClassNotFoundException e) {
      return null;
    }
  }
}
