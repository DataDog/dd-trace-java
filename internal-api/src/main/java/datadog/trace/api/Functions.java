package datadog.trace.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.function.Function.identity;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Base64;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Function;

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
   * Base64-decodes bytes that occasionally aren't valid Base64 at all (e.g. a misconfigured
   * upstream producer sending mixed encodings), without paying for {@link Base64}'s decoder
   * throwing and filling in a stack trace on every one of those failures.
   *
   * <p>Tracks a simple hysteresis "breaker": after a real decode failure it switches into a guarded
   * state and cheaply pre-checks the Base64 alphabet before calling into {@link Base64}'s decoder,
   * until enough consecutive inputs look valid again to close the guard. A precheck-detected
   * failure throws {@link DefinitelyNotBase64Exception}, a stack-trace-free stand-in, so the {@link
   * #decode} contract still matches {@link Base64}'s own (throws {@link IllegalArgumentException}
   * on failure); a failure the precheck misses still throws the JDK's own exception, unmodified,
   * with its real message and stack trace.
   *
   * <p>Not safe for concurrent use by multiple threads expecting independent guard state — the
   * guard state is a plain (non-volatile, non-atomic) field on purpose: it's advisory hysteresis,
   * not correctness-critical, so a stale read across threads just costs one extra precheck or one
   * extra real exception.
   */
  public static final class GuardedBase64Decode {

    private static final int CLOSE_THRESHOLD = 20;
    private static final boolean[] BASE64_ALPHABET = buildAlphabetTable();

    private static boolean[] buildAlphabetTable() {
      boolean[] table = new boolean[256];
      String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
      for (int i = 0; i < alphabet.length(); i++) {
        table[alphabet.charAt(i)] = true;
      }
      return table;
    }

    // Branch-free per byte on purpose: a data-dependent early exit helps the rare "bad byte
    // early" case but hurts the common valid case, which always scans the whole buffer anyway.
    private static boolean looksLikeBase64(byte[] bytes) {
      if (bytes.length == 0 || (bytes.length & 3) != 0) {
        return false;
      }
      boolean valid = true;
      for (byte b : bytes) {
        valid &= BASE64_ALPHABET[b & 0xFF];
      }
      return valid;
    }

    /** Thrown only when the alphabet precheck already knows decoding would fail. */
    public static final class DefinitelyNotBase64Exception extends IllegalArgumentException {
      private static final String MESSAGE = "Input is not valid Base64";

      DefinitelyNotBase64Exception() {
        super(MESSAGE);
      }

      // No caller needs a stack trace for a failure we already knew about before calling into
      // Base64's decoder, so skip the walk that gives java.util.Base64's own exception its cost.
      @Override
      public synchronized Throwable fillInStackTrace() {
        return this;
      }
    }

    // Single-word countdown: 0 == closed (no precheck), >0 == guarded, counting down to close.
    private int state;

    /** Decodes {@code bytes}, throwing {@link IllegalArgumentException} on failure. */
    public String decode(byte[] bytes) {
      if (state > 0 && !looksLikeBase64(bytes)) {
        throw new DefinitelyNotBase64Exception();
      }
      try {
        String result = new String(Base64.getDecoder().decode(bytes), UTF_8);
        if (state > 0) {
          state--;
        }
        return result;
      } catch (IllegalArgumentException e) {
        state = CLOSE_THRESHOLD;
        throw e;
      }
    }

    /** Decodes {@code bytes}, returning {@code null} on failure instead of throwing. */
    public String decodeOrNull(byte[] bytes) {
      try {
        return decode(bytes);
      } catch (IllegalArgumentException e) {
        return null;
      }
    }
  }
}
