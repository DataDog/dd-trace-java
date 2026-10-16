package datadog.trace.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.function.Function.identity;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.util.AdaptiveLatch;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.annotation.Nullable;

public final class Functions {

  private Functions() {}

  public abstract static class Concatenate {

    public CharSequence concatenate(CharSequence left, CharSequence right) {
      return UTF8BytesString.create(String.valueOf(left) + right);
    }
  }

  public static final class Suffix extends Concatenate
      implements Function<CharSequence, CharSequence> {
    private final CharSequence suffix;
    private final Function<CharSequence, CharSequence> transformer;

    public Suffix(CharSequence suffix, Function<CharSequence, CharSequence> transformer) {
      this.suffix = suffix;
      this.transformer = transformer;
    }

    public Suffix(String suffix) {
      this(suffix, identity());
    }

    @Override
    public CharSequence apply(CharSequence key) {
      return concatenate(transformer.apply(key), suffix);
    }
  }

  public static final class Prefix extends Concatenate
      implements Function<CharSequence, CharSequence> {
    private final CharSequence prefix;
    private final Function<CharSequence, CharSequence> transformer;

    public Prefix(CharSequence prefix, Function<CharSequence, CharSequence> transformer) {
      this.prefix = prefix;
      this.transformer = transformer;
    }

    public Prefix(CharSequence prefix) {
      this(prefix, identity());
    }

    @Override
    public CharSequence apply(CharSequence key) {
      return concatenate(prefix, transformer.apply(key));
    }
  }

  public abstract static class Join
      implements BiFunction<CharSequence, CharSequence, CharSequence> {
    protected final CharSequence joiner;
    protected final Function<CharSequence, CharSequence> transformer;

    protected Join(CharSequence joiner, Function<CharSequence, CharSequence> transformer) {
      this.joiner = joiner;
      this.transformer = transformer;
    }

    @Override
    public CharSequence apply(CharSequence left, CharSequence right) {
      return UTF8BytesString.create(String.valueOf(left) + joiner + right);
    }

    public abstract Function<CharSequence, CharSequence> curry(CharSequence specialisation);
  }

  public static class PrefixJoin extends Join {

    public PrefixJoin(CharSequence joiner, Function<CharSequence, CharSequence> transformer) {
      super(joiner, transformer);
    }

    @Override
    public Function<CharSequence, CharSequence> curry(CharSequence specialisation) {
      return new Prefix(String.valueOf(specialisation) + joiner, transformer);
    }

    public static PrefixJoin of(
        CharSequence joiner, Function<CharSequence, CharSequence> transformer) {
      return new PrefixJoin(joiner, transformer);
    }

    public static PrefixJoin of(String joiner) {
      return of(joiner, identity());
    }

    public static final PrefixJoin ZERO = of("");
  }

  public static class SuffixJoin extends Join {

    public SuffixJoin(CharSequence joiner, Function<CharSequence, CharSequence> transformer) {
      super(joiner, transformer);
    }

    @Override
    public Function<CharSequence, CharSequence> curry(CharSequence specialisation) {
      return new Suffix(String.valueOf(joiner) + specialisation, transformer);
    }

    public static SuffixJoin of(
        CharSequence joiner, Function<CharSequence, CharSequence> transformer) {
      return new SuffixJoin(joiner, transformer);
    }

    public static SuffixJoin of(CharSequence joiner) {
      return of(joiner, identity());
    }

    public static final SuffixJoin ZERO = of("");
  }

  public static final Function<String, UTF8BytesString> UTF8_ENCODE = UTF8BytesString::create;

  public static final class LowerCase implements Function<String, String> {

    public static final LowerCase INSTANCE = new LowerCase();

    @Override
    public String apply(String key) {
      return key.toLowerCase(Locale.ROOT);
    }
  }

  public static final class ToString<T> implements Function<T, String> {

    @Override
    public String apply(T key) {
      return key.toString();
    }
  }

  public static <T> Function<?, T> newInstanceOf(Class<T> clazz) {
    return new NewInstance<>(clazz);
  }

  @SuppressWarnings("unchecked")
  private static final class NewInstance<Object, T> implements Function<Object, T> {

    private final MethodHandle methodHandle;

    private NewInstance(Class<T> type) {
      try {
        this.methodHandle =
            MethodHandles.lookup().findConstructor(type, MethodType.methodType(void.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public T apply(Object input) {
      try {
        // can't invokeExact because the return type is Object
        // in this context
        return (T) methodHandle.invoke();
      } catch (Throwable throwable) {
        return null;
      }
    }
  }

  public static final Function<byte[], String> UTF8_BYTES_TO_STRING =
      bytes -> new String(bytes, UTF_8);

  public static final Function<byte[], String> BASE64_DECODE =
      bytes -> {
        try {
          return new String(Base64.getDecoder().decode(bytes), UTF_8);
        } catch (final Exception ignored) {
          return null;
        }
      };

  /**
   * Base64-decodes bytes that are occasionally not Base64 at all, for example header values from a
   * misconfigured producer that mixes encodings, without paying for {@link Base64}'s decoder to
   * throw, and fill in a stack trace, on every one of them. Returns {@code null} for input that
   * does not decode, like {@link #BASE64_DECODE}.
   *
   * <p>Decodes with {@link Base64#getDecoder()} until it fails, then with {@link
   * #decodeOrNull(byte[])}, an exception-free decoder that accepts exactly what {@link
   * Base64#getDecoder()} accepts, until {@code CLOSE_AFTER} consecutive inputs decode again (see
   * {@link AdaptiveLatch}).
   *
   * <p>One instance can be shared across threads: its state is advisory, so a stale read costs one
   * extra cautious decode or one extra real failure, never a wrong result.
   */
  public static final class GuardedBase64Decode
      extends AdaptiveLatch<byte[], String, IllegalArgumentException> {
    /** Each byte's 6-bit value in the basic Base64 alphabet, {@code -2} for padding, else -1. */
    private static final byte[] FROM_BASE64 = buildDecodeTable();

    private static final int PADDING = -2;

    private static byte[] buildDecodeTable() {
      byte[] table = new byte[256];
      Arrays.fill(table, (byte) -1);
      String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
      for (int i = 0; i < alphabet.length(); i++) {
        table[alphabet.charAt(i)] = (byte) i;
      }
      table['='] = PADDING;
      return table;
    }

    /**
     * The rent-or-buy break-even (see {@link AdaptiveLatch}), from {@code Base64DecodeBenchmark} on
     * JDK 17 for header-sized values: a JDK decoder failure costs about 913 ns more than a
     * rejection here at stack depth 0 and about 2,468 ns at depth 50, and on valid input this
     * decoder costs about 27 ns more than the JDK's, giving 34 to 91. A consumer's stack is deeper
     * than a benchmark's. On JDK 8 the two decoders cost about the same on valid input, so the
     * value barely matters there.
     */
    private static final int CLOSE_AFTER = 64;

    public GuardedBase64Decode() {
      super(IllegalArgumentException.class, CLOSE_AFTER);
    }

    @Override
    protected String apply(byte[] bytes) {
      return new String(Base64.getDecoder().decode(bytes), UTF_8);
    }

    @Override
    protected String applySafely(byte[] bytes) {
      String decoded = decodeOrNull(bytes);
      return decoded != null ? decoded : reject(bytes);
    }

    /**
     * Decodes {@code src} as {@link Base64#getDecoder()} would, returning {@code null} where it
     * would throw. Follows the same rules: padding is optional, but if present must complete the
     * final unit, and nothing may follow it; a final unit of one character is rejected.
     */
    @Nullable
    static String decodeOrNull(byte[] src) {
      final int length = src.length;
      if (length == 0) {
        return "";
      }
      // allocated up front only if the first unit is clean, so input that is bad from the start
      // costs no allocation; input that goes bad later still does, since one pass cannot know in
      // advance. If the first unit is not clean, no unit can complete before the loop meets its
      // bad or padding byte, so the loop never writes to a null dst.
      byte[] dst =
          length >= 4
                  && (FROM_BASE64[src[0] & 0xFF]
                          | FROM_BASE64[src[1] & 0xFF]
                          | FROM_BASE64[src[2] & 0xFF]
                          | FROM_BASE64[src[3] & 0xFF])
                      >= 0
              ? new byte[3 * ((length + 3) / 4)]
              : null;
      int dp = 0;
      int bits = 0;
      // the bit position of the next character within its 4-character unit
      int shift = 18;
      int sp = 0;
      while (sp < length) {
        final int b = FROM_BASE64[src[sp++] & 0xFF];
        if (b < 0) {
          // padding is only legal after two or three characters of a unit, and after two it
          // must be doubled
          if (b != PADDING || shift == 18 || (shift == 6 && (sp == length || src[sp++] != '='))) {
            return null;
          }
          break;
        }
        bits |= b << shift;
        shift -= 6;
        if (shift < 0) {
          dst[dp++] = (byte) (bits >> 16);
          dst[dp++] = (byte) (bits >> 8);
          dst[dp++] = (byte) bits;
          shift = 18;
          bits = 0;
        }
      }
      // a dangling single character, or anything after the padding
      if (shift == 12 || sp < length) {
        return null;
      }
      if (shift != 18) {
        if (dst == null) {
          // no full unit: at most two bytes
          dst = new byte[2];
        }
        dst[dp++] = (byte) (bits >> 16);
        if (shift == 0) {
          dst[dp++] = (byte) (bits >> 8);
        }
      }
      if (dst == null) {
        return "";
      }
      return new String(dst, 0, dp, UTF_8);
    }
  }
}
