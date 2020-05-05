package datadog.trace.core;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.DDComponents;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

public class StringTables {

  public static final String SERVICE = "service";
  public static final String NAME = "name";
  public static final String RESOURCE = "resource";
  public static final String TRACE_ID = "trace_id";
  public static final String SPAN_ID = "span_id";
  public static final String PARENT_ID = "parent_id";
  public static final String START = "start";
  public static final String DURATION = "duration";
  public static final String TYPE = "type";
  public static final String ERROR = "error";
  public static final String METRICS = "metrics";
  public static final String META = "meta";

  // intentionally not thread safe; must be maintained to be effectively immutable
  // if a constant registration API is added, should be ensured that this is only used during
  // startup
  private static final Map<String, byte[]> UTF8_INTERN_TABLE = new HashMap<>(256);

  static {
    internConstantsUTF8(StringTables.class);
    internConstantsUTF8(Tags.class);
    internConstantsUTF8(DDSpanTypes.class);
    internConstantsUTF8(DDComponents.class);
  }

  public static byte[] getBytesUTF8(String value) {
    byte[] bytes = UTF8_INTERN_TABLE.get(value);
    return null == bytes ? value.getBytes(UTF_8) : bytes;
  }

  private static void internConstantsUTF8(Class<?> clazz) {
    for (Field field : clazz.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers())
          && Modifier.isPublic(field.getModifiers())
          && field.getType() == String.class) {
        try {
          intern(UTF8_INTERN_TABLE, (String) field.get(null), UTF_8);
        } catch (IllegalAccessException e) {
          // won't happen
        }
      }
    }
  }

  private static void intern(Map<String, byte[]> table, String value, Charset encoding) {
    table.put(value, value.getBytes(encoding));
  }
}
