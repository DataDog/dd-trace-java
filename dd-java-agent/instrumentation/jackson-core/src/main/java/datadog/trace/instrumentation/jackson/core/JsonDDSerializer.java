package datadog.trace.instrumentation.jackson.core;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonDDSerializer extends StdSerializer<Object> {
  private static final Map<Class<?>, Map<String, Object>> schemaCache = new HashMap<>();
  private static final Logger log = LoggerFactory.getLogger(JsonDDSerializer.class);

  public JsonDDSerializer() {
    this(null);
  }

  public JsonDDSerializer(Class<Object> t) {
    super(t);
  }

  @Override
  public void serialize(Object value, JsonGenerator gen, SerializerProvider provider)
      throws IOException {
    AgentSpan span = activeSpan();
    try {
      // Serialize the actual object data
      gen.writeObject(value);
    } catch (Exception e) {

    }
  }

  private Map<String, Object> collectOpenAPISchema(Class<?> clazz) {
    if (schemaCache.containsKey(clazz)) {
      return schemaCache.get(clazz);
    }

    Map<String, Object> schema = new HashMap<>();
    Map<String, Object> properties = new HashMap<>();

    for (Field field : clazz.getDeclaredFields()) {
      field.setAccessible(true);

      Map<String, Object> fieldSchema = new HashMap<>();
      fieldSchema.put("type", getJsonType(field.getType())); // Detect type

      properties.put(field.getName(), fieldSchema);
    }

    schema.put("type", "object");
    schema.put("properties", properties);
    schemaCache.put(clazz, schema);
    return schema;
  }

  private String getJsonType(Class<?> clazz) {
    if (Integer.class.isAssignableFrom(clazz) || int.class.isAssignableFrom(clazz)) {
      return "integer";
    } else if (Boolean.class.isAssignableFrom(clazz) || boolean.class.isAssignableFrom(clazz)) {
      return "boolean";
    } else if (Long.class.isAssignableFrom(clazz) || long.class.isAssignableFrom(clazz)) {
      return "integer";
    } else if (Double.class.isAssignableFrom(clazz) || double.class.isAssignableFrom(clazz)) {
      return "number";
    } else if (Float.class.isAssignableFrom(clazz) || float.class.isAssignableFrom(clazz)) {
      return "number";
    } else if (String.class.isAssignableFrom(clazz)) {
      return "string";
    } else if (java.util.Date.class.isAssignableFrom(clazz)
        || java.sql.Date.class.isAssignableFrom(clazz)) {
      return "string"; // Format can be added for date type
    } else {
      return "object"; // Default for other types
    }
  }

  public Map<String, Object> getOpenAPISchema(Class<?> clazz) {
    return collectOpenAPISchema(clazz);
  }
}
