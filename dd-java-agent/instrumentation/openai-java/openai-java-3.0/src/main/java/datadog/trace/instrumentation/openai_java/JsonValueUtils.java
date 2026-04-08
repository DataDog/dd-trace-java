package datadog.trace.instrumentation.openai_java;

import com.openai.core.JsonValue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class JsonValueUtils {
  private JsonValueUtils() {}

  public static Map<String, Object> jsonValueMapToObject(Map<String, JsonValue> map) {
    Map<String, Object> result = new HashMap<>();
    for (Map.Entry<String, JsonValue> entry : map.entrySet()) {
      result.put(entry.getKey(), jsonValueToObject(entry.getValue()));
    }
    return result;
  }

  public static Object jsonValueToObject(JsonValue value) {
    if (value == null) {
      return null;
    }
    Optional<String> str = value.asString();
    if (str.isPresent()) {
      return str.get();
    }
    Optional<Number> num = value.asNumber();
    if (num.isPresent()) {
      return num.get();
    }
    Optional<Boolean> bool = value.asBoolean();
    if (bool.isPresent()) {
      return bool.get();
    }
    Optional<Map<String, JsonValue>> obj = value.asObject();
    if (obj.isPresent()) {
      return jsonValueMapToObject(obj.get());
    }
    Optional<List<JsonValue>> arr = value.asArray();
    if (arr.isPresent()) {
      List<Object> list = new ArrayList<>();
      for (JsonValue item : arr.get()) {
        list.add(jsonValueToObject(item));
      }
      return list;
    }
    return null;
  }
}
