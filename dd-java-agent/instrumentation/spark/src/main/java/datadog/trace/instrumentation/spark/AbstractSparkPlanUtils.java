package datadog.trace.instrumentation.spark;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import org.apache.spark.sql.catalyst.plans.QueryPlan;
import org.apache.spark.sql.catalyst.trees.TreeNode;
import scala.Option;
import scala.collection.Iterable;

public abstract class AbstractSparkPlanUtils {
  private final ObjectMapper mapper =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  public abstract Map<String, String> extractPlanProduct(TreeNode node);

  // Should only call on final values being written to `meta`
  public String writeObjectToString(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (IOException e) {
      return null;
    }
  }

  // Should really only return valid JSON types (Array, Map, String, Boolean, Number, null)
  public Object parsePlanProduct(Object value) {
    if (value == null) {
      return "null";
    } else if (value instanceof Iterable) {
      ArrayList<Object> list = new ArrayList<>();
      ((Iterable) value).foreach(item -> list.add(parsePlanProduct(item)));
      return list;
    } else if (value instanceof Option) {
      return parsePlanProduct(((Option) value).getOrElse(() -> "none"));
    } else if (value instanceof QueryPlan) { // Filter out values referencing child nodes
      return null;
    } else if (value instanceof Boolean || Number.class.isInstance(value)) {
      return value;
    } else {
      return value.toString();
    }
  }
}
