package datadog.trace.core;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.sampling.RateByServiceSampler;
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
  private static final Map<CharSequence, byte[]> UTF8_INTERN_KEYS_TABLE = new HashMap<>(256);

  static {
    internConstantsUTF8(DDSpanContext.class, UTF8_INTERN_KEYS_TABLE);
    internConstantsUTF8(DDTags.class, UTF8_INTERN_KEYS_TABLE);
    internConstantsUTF8(Tags.class, UTF8_INTERN_KEYS_TABLE);
    internConstantsUTF8(InstrumentationTags.class, UTF8_INTERN_KEYS_TABLE);
    intern(UTF8_INTERN_KEYS_TABLE, RateByServiceSampler.SAMPLING_AGENT_RATE, UTF_8);
  }

  public static byte[] getKeyBytesUTF8(CharSequence value) {
    return UTF8_INTERN_KEYS_TABLE.get(value);
  }

  private static void internConstantsUTF8(Class<?> clazz, Map<CharSequence, byte[]> map) {
    for (Field field : clazz.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers())
          && Modifier.isPublic(field.getModifiers())
          && field.getType() == String.class) {
        try {
          intern(map, (String) field.get(null), UTF_8);
        } catch (IllegalAccessException e) {
          // won't happen
        }
      }
    }
  }

  private static void intern(Map<CharSequence, byte[]> table, String value, Charset encoding) {
    byte[] bytes = value.getBytes(encoding);
    table.put(value, bytes);
  }
}
