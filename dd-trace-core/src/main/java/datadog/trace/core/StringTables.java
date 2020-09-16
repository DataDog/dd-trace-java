package datadog.trace.core;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.CommonTagValues;
import datadog.trace.bootstrap.instrumentation.api.DDComponents;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.sampling.RateByServiceSampler;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
  private static final Map<CharSequence, byte[]> UTF8_INTERN_TAGS_TABLE = new HashMap<>(256);
  private static final int MAX_TAGS_LENGTH;
  private static final long[] TAGS_FIRST_CHAR_IS_PRESENT = new long[4];

  static {
    internConstantsUTF8(DDSpanContext.class, UTF8_INTERN_KEYS_TABLE, null);
    internConstantsUTF8(DDTags.class, UTF8_INTERN_KEYS_TABLE, null);
    internConstantsUTF8(Tags.class, UTF8_INTERN_KEYS_TABLE, null);
    internConstantsUTF8(InstrumentationTags.class, UTF8_INTERN_KEYS_TABLE, null);
    internConstantsUTF8(DDSpanTypes.class, UTF8_INTERN_TAGS_TABLE, TAGS_FIRST_CHAR_IS_PRESENT);
    internConstantsUTF8(DDComponents.class, UTF8_INTERN_TAGS_TABLE, TAGS_FIRST_CHAR_IS_PRESENT);
    internConstantsUTF8(CommonTagValues.class, UTF8_INTERN_TAGS_TABLE, TAGS_FIRST_CHAR_IS_PRESENT);
    intern(
        UTF8_INTERN_TAGS_TABLE, Config.get().getServiceName(), UTF_8, TAGS_FIRST_CHAR_IS_PRESENT);
    intern(UTF8_INTERN_TAGS_TABLE, Config.get().getRuntimeId(), UTF_8, TAGS_FIRST_CHAR_IS_PRESENT);
    intern(UTF8_INTERN_KEYS_TABLE, RateByServiceSampler.SAMPLING_AGENT_RATE, UTF_8, null);
    UTF8_INTERN_TAGS_TABLE.put("", new byte[0]);
    MAX_TAGS_LENGTH = maxKeyLength(UTF8_INTERN_TAGS_TABLE.keySet());
  }

  public static byte[] getKeyBytesUTF8(CharSequence value) {
    return UTF8_INTERN_KEYS_TABLE.get(value);
  }

  public static byte[] getTagBytesUTF8(CharSequence value) {
    return tagMaybeInterned(value) ? UTF8_INTERN_TAGS_TABLE.get(value) : null;
  }

  private static void internConstantsUTF8(
      Class<?> clazz, Map<CharSequence, byte[]> map, long[] firstByteBitmap) {
    for (Field field : clazz.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers())
          && Modifier.isPublic(field.getModifiers())
          && field.getType() == String.class) {
        try {
          intern(map, (String) field.get(null), UTF_8, firstByteBitmap);
        } catch (IllegalAccessException e) {
          // won't happen
        }
      }
    }
  }

  private static void intern(
      Map<CharSequence, byte[]> table, String value, Charset encoding, long[] firstByteBitmap) {
    byte[] bytes = value.getBytes(encoding);
    if (null != firstByteBitmap && bytes.length > 0) {
      int bit = bytes[0] & 0xFF;
      firstByteBitmap[bit >>> 6] |= 1L << bit;
    }
    table.put(value, bytes);
  }

  private static boolean tagMaybeInterned(final CharSequence tag) {
    if (null == tag || tag.length() > MAX_TAGS_LENGTH) {
      return false;
    }
    if (tag.length() > 0) {
      final char first = tag.charAt(0);
      if (first < 256 // should virtually always be the case
          && (TAGS_FIRST_CHAR_IS_PRESENT[first >>> 6] & (1L << first)) == 0) {
        return false;
      }
    }
    return true;
  }

  private static int maxKeyLength(final Set<CharSequence> keys) {
    int max = 0;
    for (CharSequence key : keys) {
      max = Math.max(key.length(), max);
    }
    return max;
  }
}
