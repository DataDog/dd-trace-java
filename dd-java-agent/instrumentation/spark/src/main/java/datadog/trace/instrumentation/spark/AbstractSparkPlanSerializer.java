package datadog.trace.instrumentation.spark;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.apache.spark.sql.catalyst.plans.QueryPlan;
import org.apache.spark.sql.catalyst.plans.physical.Partitioning;
import org.apache.spark.sql.catalyst.trees.TreeNode;
import org.apache.spark.sql.execution.SparkPlan;
import scala.Option;
import scala.collection.Iterable;
import scala.collection.JavaConverters;

// An extension of how Spark translates `SparkPlan`s to `SparkPlanInfo`, see here:
// https://github.com/apache/spark/blob/v3.5.0/sql/core/src/main/scala/org/apache/spark/sql/execution/SparkPlanInfo.scala#L54
public abstract class AbstractSparkPlanSerializer {
  private final int MAX_DEPTH = 4;
  private final int MAX_LENGTH = 50;
  private final ObjectMapper mapper = AbstractDatadogSparkListener.objectMapper;

  private final String SPARK_PKG_NAME = "org.apache.spark";
  private final Set<String> SAFE_CLASS_NAMES =
      new HashSet<>(
          Arrays.asList(
              SPARK_PKG_NAME + ".Partitioner", // not a product or TreeNode
              SPARK_PKG_NAME
                  + ".sql.catalyst.expressions.Attribute", // avoid data type added by simpleString
              SPARK_PKG_NAME + ".sql.catalyst.optimizer.BuildSide", // enum (v3+)
              SPARK_PKG_NAME + ".sql.catalyst.plans.JoinType", // enum
              SPARK_PKG_NAME
                  + ".sql.catalyst.plans.physical.BroadcastMode", // not a product or TreeNode
              SPARK_PKG_NAME
                  + ".sql.execution.ShufflePartitionSpec", // not a product or TreeNode (v3+)
              SPARK_PKG_NAME + ".sql.execution.exchange.ShuffleOrigin" // enum (v3+)
              ));

  // Add class here if we want to break inheritance and interface traversal early when we see
  // this class. Any class added must be a class whose parents we do not want to match
  // (inclusive of the class itself).
  private final Set<String> NEGATIVE_CACHE_CLASSES =
      new HashSet<>(
          Arrays.asList(
              "java.io.Serializable",
              "java.lang.Object",
              "scala.Equals",
              "scala.Product",
              SPARK_PKG_NAME + ".sql.catalyst.InternalRow",
              SPARK_PKG_NAME + ".sql.catalyst.expressions.Expression",
              SPARK_PKG_NAME + ".sql.catalyst.expressions.UnaryExpression",
              SPARK_PKG_NAME + ".sql.catalyst.expressions.Unevaluable",
              SPARK_PKG_NAME + ".sql.catalyst.trees.TreeNode"));

  public abstract String getKey(int idx, TreeNode node);

  public Map<String, String> extractFormattedProduct(SparkPlan plan) {
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
    } else if (value instanceof String || value instanceof Boolean || value instanceof Number) {
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
    } else if (Partitioning.class.isInstance(value)) {
      if (value instanceof TreeNode && depth < MAX_DEPTH) {
        HashMap<String, Object> inner = new HashMap<>();
        inner.put(
            value.getClass().getSimpleName(), safeParseTreeNode(((TreeNode) value), depth + 1));
        return inner;
      } else {
        return value.toString();
      }
    } else if (traversedInstanceOf(value, SAFE_CLASS_NAMES, NEGATIVE_CACHE_CLASSES)) {
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
    } catch (NullPointerException
        | NoSuchMethodException
        | IllegalAccessException
        | InvocationTargetException exception) {
      try {
        // Attempt the Spark v2 `simpleString` signature
        return TreeNode.class.getDeclaredMethod("simpleString").invoke(value).toString();
      } catch (NullPointerException
          | NoSuchMethodException
          | IllegalAccessException
          | InvocationTargetException innerException) {
      }

      return null;
    }
  }

  private boolean traversedInstanceOf(
      Object value, Set<String> expectedClasses, Set<String> negativeCache) {
    if (instanceOf(value.getClass(), expectedClasses, negativeCache)) {
      return true;
    }

    // Traverse up inheritance tree to check for matches
    int lim = 0;
    Class currClass = value.getClass();
    while (currClass.getSuperclass() != null && lim < MAX_DEPTH) {
      currClass = currClass.getSuperclass();
      if (negativeCache.contains(currClass.getName())) {
        // don't traverse known paths
        break;
      }
      if (instanceOf(currClass, expectedClasses, negativeCache)) {
        return true;
      }
      lim += 1;
    }

    return false;
  }

  private boolean instanceOf(Class cls, Set<String> expectedClasses, Set<String> negativeCache) {
    // Match on strings to avoid class loading errors
    if (expectedClasses.contains(cls.getName())) {
      return true;
    }

    // Check interfaces as well
    for (Class interfaceClass : cls.getInterfaces()) {
      if (!negativeCache.contains(interfaceClass.getName())
          && instanceOf(interfaceClass, expectedClasses, negativeCache)) {
        return true;
      }
    }

    return false;
  }
}
