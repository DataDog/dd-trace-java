package datadog.trace.core.datastreams;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class JsonSchemaGenerator {
  public static String generateJsonSchema(String jsonString) {
    Moshi moshi = new Moshi.Builder().build();
    Type type = Types.newParameterizedType(Map.class, String.class, Object.class);
    JsonAdapter<Map<String, Object>> adapter = moshi.adapter(type);

    try {
      Object jsonValue = adapter.fromJson(jsonString);
      return generateJsonSchema(jsonValue);
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  private static String generateJsonSchema(Object jsonValue) {
    List<String> schema = new ArrayList<>();
    try {
      extractFieldTypes("", jsonValue, schema);
    } catch (IOException e) {
      e.printStackTrace();
    }
    return String.join(",\n", schema);
  }

  private static void extractFieldTypes(String fieldName, Object jsonValue, List<String> schema)
      throws IOException {
    if (jsonValue == null) {
      // Ignore null values
    } else if (jsonValue instanceof Boolean) {
      schema.add(createSchemaEntry(fieldName, "boolean"));
    } else if (jsonValue instanceof Number) {
      if (jsonValue instanceof Integer || jsonValue instanceof Long) {
        schema.add(createSchemaEntry(fieldName, "integer"));
      } else {
        schema.add(createSchemaEntry(fieldName, "number"));
      }
    } else if (jsonValue instanceof String) {
      schema.add(createSchemaEntry(fieldName, "string"));
    } else if (jsonValue instanceof List) {
      extractListTypes(fieldName, (List<?>) jsonValue, schema);
    } else if (jsonValue instanceof Map) {
      extractMapTypes(fieldName, (Map<?, ?>) jsonValue, schema);
    } else {
      throw new JsonDataException("Unsupported data type: " + jsonValue.getClass());
    }
  }

  private static void extractListTypes(String fieldName, List<?> list, List<String> schema)
      throws IOException {
    if (list.isEmpty()) {
      // Ignore empty lists
    } else {
      Object firstElement = list.get(0);
      String nestedFieldName = fieldName.isEmpty() ? "items" : fieldName + ".items";
      extractFieldTypes(nestedFieldName, firstElement, schema);
    }
  }

  private static void extractMapTypes(String fieldName, Map<?, ?> map, List<String> schema)
      throws IOException {
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      String nestedFieldName =
          fieldName.isEmpty() ? String.valueOf(entry.getKey()) : fieldName + "." + entry.getKey();
      extractFieldTypes(nestedFieldName, entry.getValue(), schema);
    }
  }

  private static String createSchemaEntry(String fieldName, String fieldType) {
    return "{\n  \"name\": \"" + fieldName + "\",\n  \"type\": \"" + fieldType + "\"\n}";
  }
}
