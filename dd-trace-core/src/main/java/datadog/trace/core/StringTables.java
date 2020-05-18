package datadog.trace.core;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.DDComponents;
import datadog.trace.bootstrap.instrumentation.api.DDSpanNames;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

public class StringTables {

  public static final byte[] SERVICE = "service".getBytes(UTF_8);
  public static final byte[] NAME = "name".getBytes(UTF_8);
  public static final byte[] RESOURCE = "resource".getBytes(UTF_8);
  public static final byte[] TRACE_ID = "trace_id".getBytes(UTF_8);
  public static final byte[] SPAN_ID = "span_id".getBytes(UTF_8);
  public static final byte[] PARENT_ID = "parent_id".getBytes(UTF_8);
  public static final byte[] START = "start".getBytes(UTF_8);
  public static final byte[] DURATION = "duration".getBytes(UTF_8);
  public static final byte[] TYPE = "type".getBytes(UTF_8);
  public static final byte[] ERROR = "error".getBytes(UTF_8);
  public static final byte[] METRICS = "metrics".getBytes(UTF_8);
  public static final byte[] META = "meta".getBytes(UTF_8);

  // intentionally not thread safe; must be maintained to be effectively immutable
  // if a constant registration API is added, should be ensured that this is only used during
  // startup
  private static final Map<String, byte[]> UTF8_INTERN_TABLE = new HashMap<>(256);

  static {
    internConstantsUTF8(StringTables.class);
    internConstantsUTF8(Tags.class);
    internConstantsUTF8(DDSpanTypes.class);
    internConstantsUTF8(DDComponents.class);
    internConstantsUTF8(DDSpanNames.class);
  }

  public static byte[] getBytesUTF8(String value) {
    return UTF8_INTERN_TABLE.get(value);
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
