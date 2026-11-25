package datadog.trace.bootstrap.jit;

import java.util.UUID;

/**
 * Replacements for JDK methods that are slow on older JDKs only. The rewriter applies each one only
 * on the JDK versions where it pays; on any JDK the result is the same as the original.
 */
public final class JdkRewrites {
  private JdkRewrites() {}

  private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

  /**
   * {@link UUID#toString()}: JDK 8 builds it from several substrings and a concatenation; JDK 9+
   * already formats it directly.
   */
  public static String uuidToString(UUID uuid) {
    long most = uuid.getMostSignificantBits(); // a null uuid throws like the original
    long least = uuid.getLeastSignificantBits();
    char[] chars = new char[36];
    hex(chars, 0, most >>> 32, 8);
    chars[8] = '-';
    hex(chars, 9, most >>> 16, 4);
    chars[13] = '-';
    hex(chars, 14, most, 4);
    chars[18] = '-';
    hex(chars, 19, least >>> 48, 4);
    chars[23] = '-';
    hex(chars, 24, least, 12);
    return new String(chars);
  }

  /** Writes the low {@code digits} hex digits of {@code value}, most significant first. */
  private static void hex(char[] chars, int at, long value, int digits) {
    for (int i = at + digits - 1; i >= at; i--) {
      chars[i] = HEX_DIGITS[(int) (value & 0xF)];
      value >>>= 4;
    }
  }

  private static final ClassValue<String> SIMPLE_NAMES =
      new ClassValue<String>() {
        @Override
        protected String computeValue(Class<?> type) {
          return type
              .getSimpleName(); // if this throws, nothing is cached and the next call retries
        }
      };

  /**
   * {@link Class#getSimpleName()}: computed on every call before JDK 11, which caches it. The
   * identity of the returned String isn't specified (it already differs between JDK versions).
   */
  public static String simpleName(Class<?> type) {
    return SIMPLE_NAMES.get(type); // a null type throws like the original
  }
}
