package datadog.trace.bootstrap.jit;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Currency;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Drop-in replacements for short call sequences that build a helper object only to use it once,
 * such as {@code Pattern.compile(regex).matcher(input).matches()}, substituted at their call sites.
 *
 * <p>Each helper takes exactly the operands of the original sequence and returns exactly what it
 * returns. Objects that the sequence builds are cached only when they never escape or when a copy
 * is indistinguishable from a new one. Caches are tiered (see {@link TieredCache}), and any hidden
 * input the original reads, such as the default locale, is read on every call and checked against
 * the cached object. Everything else (including {@code null} arguments and invalid patterns) runs
 * the original code, which then throws its own exceptions in its own order. The only visible
 * difference is one extra stack frame.
 */
public final class FusedRewrites {
  private FusedRewrites() {}

  private static final TieredCache formatters = new TieredCache();
  private static final TieredCache decimalFormats = new TieredCache();

  /** {@code Pattern.compile(regex).matcher(input).matches()}. */
  public static boolean compileMatches(String regex, CharSequence input) {
    return compile(regex).matcher(input).matches();
  }

  /** {@code Pattern.compile(regex).matcher(input).find()}. */
  public static boolean compileFind(String regex, CharSequence input) {
    return compile(regex).matcher(input).find();
  }

  /** {@code Pattern.compile(regex).matcher(input).lookingAt()}. */
  public static boolean compileLookingAt(String regex, CharSequence input) {
    return compile(regex).matcher(input).lookingAt();
  }

  /** {@code Pattern.compile(regex).split(input)}. */
  public static String[] compileSplit(String regex, CharSequence input) {
    return compile(regex).split(input);
  }

  /** {@code Pattern.compile(regex)}, from the cache when hot; the pattern must not escape. */
  private static Pattern compile(String regex) {
    if (null != regex) {
      Pattern pattern = PatternCache.get(regex);
      if (null != pattern) {
        return pattern;
      }
    }
    return Pattern.compile(regex);
  }

  /** {@code t.format(DateTimeFormatter.ofPattern(pattern))}. */
  public static String temporalFormat(LocalDate t, String pattern) {
    return t.format(ofPattern(pattern));
  }

  /** {@code t.format(DateTimeFormatter.ofPattern(pattern))}. */
  public static String temporalFormat(LocalDateTime t, String pattern) {
    return t.format(ofPattern(pattern));
  }

  /** {@code t.format(DateTimeFormatter.ofPattern(pattern))}. */
  public static String temporalFormat(LocalTime t, String pattern) {
    return t.format(ofPattern(pattern));
  }

  /** {@code t.format(DateTimeFormatter.ofPattern(pattern))}. */
  public static String temporalFormat(ZonedDateTime t, String pattern) {
    return t.format(ofPattern(pattern));
  }

  /** {@code t.format(DateTimeFormatter.ofPattern(pattern))}. */
  public static String temporalFormat(OffsetDateTime t, String pattern) {
    return t.format(ofPattern(pattern));
  }

  /** {@code t.format(DateTimeFormatter.ofPattern(pattern))}. */
  public static String temporalFormat(OffsetTime t, String pattern) {
    return t.format(ofPattern(pattern));
  }

  /** {@code DateTimeFormatter.ofPattern(pattern).format(t)}. */
  public static String formatterFormat(String pattern, TemporalAccessor t) {
    return ofPattern(pattern).format(t);
  }

  /**
   * {@link DateTimeFormatter#ofPattern(String)}, from the cache when hot. The formatter is
   * immutable, and the only state its creation reads is the default FORMAT locale, which is checked
   * on every call.
   */
  static DateTimeFormatter ofPattern(String pattern) {
    if (null != pattern) {
      Object entry = formatters.get(pattern);
      if (entry instanceof DateTimeFormatter) {
        DateTimeFormatter formatter = (DateTimeFormatter) entry;
        if (formatter.getLocale().equals(Locale.getDefault(Locale.Category.FORMAT))) {
          return formatter;
        }
        return promoteFormatter(pattern);
      }
      if (entry == TieredCache.PROMOTE) {
        return promoteFormatter(pattern);
      }
    }
    return DateTimeFormatter.ofPattern(pattern);
  }

  private static DateTimeFormatter promoteFormatter(String pattern) {
    DateTimeFormatter formatter;
    try {
      formatter = DateTimeFormatter.ofPattern(pattern);
    } catch (RuntimeException e) {
      formatters.demote(pattern);
      throw e; // the original's own exception
    }
    formatters.put(pattern, formatter); // keyed by the locale it captured
    return formatter;
  }

  /**
   * Whether a copy of a pristine {@code DecimalFormat} is the same as a new one. Not on JDK 9+,
   * where {@code DecimalFormatSymbols} sets up its currency lazily: a new instance starts with the
   * currency symbols last cached for its locale by any other instance (a JVM-wide cache that can
   * also be cleared by GC), so its serialized form depends on when it was built.
   */
  /** The currency {@code DecimalFormatSymbols} uses for a locale without a country. */
  private static final Currency NO_COUNTRY_CURRENCY = noCountryCurrency();

  static final boolean CLONES_DECIMAL_FORMAT =
      StringRewrites.specificationVersion() < 9
          && !hasLazyCurrency()
          && null != NO_COUNTRY_CURRENCY;

  private static Currency noCountryCurrency() {
    try {
      return Currency.getInstance("XXX");
    } catch (RuntimeException e) {
      return null; // no currency data: never copy
    }
  }

  private static boolean hasLazyCurrency() {
    try {
      DecimalFormatSymbols.class.getDeclaredField("currencyInitialized");
      return true;
    } catch (NoSuchFieldException e) {
      return false;
    } catch (RuntimeException e) {
      return true; // can't tell: assume the newer behavior
    }
  }

  /**
   * {@code new DecimalFormat(pattern)}: on JDK 8, a copy of a cached prototype when hot. The
   * prototype is never used or exposed, so each copy is the same as a new instance.
   *
   * <p>The constructor reads two hidden inputs: the default FORMAT locale, and the currency of the
   * locale's country, which can depend on the current time (a country can switch currency at a
   * cut-over date). Both are checked against the prototype on every call.
   */
  public static DecimalFormat newDecimalFormat(String pattern) {
    if (CLONES_DECIMAL_FORMAT && null != pattern) {
      Object entry = decimalFormats.get(pattern);
      if (null != entry) {
        DecimalFormat copy = copyDecimalFormat(pattern, entry);
        if (null != copy) {
          return copy;
        }
      }
    }
    return new DecimalFormat(pattern);
  }

  private static DecimalFormat copyDecimalFormat(String pattern, Object entry) {
    Locale locale = Locale.getDefault(Locale.Category.FORMAT);
    Currency currency = currency(locale);
    if (null == currency) {
      return null; // the country has no currency data: not worth caching
    }
    if (entry instanceof DecimalFormatPrototype) {
      DecimalFormatPrototype prototype = (DecimalFormatPrototype) entry;
      if (prototype.locale.equals(locale) && prototype.currency == currency) {
        return (DecimalFormat) prototype.format.clone();
      }
    } else if (entry != TieredCache.PROMOTE) {
      return null;
    }
    return promoteDecimalFormat(pattern, locale, currency);
  }

  private static DecimalFormat promoteDecimalFormat(
      String pattern, Locale locale, Currency currency) {
    DecimalFormat format;
    try {
      format = new DecimalFormat(pattern);
    } catch (RuntimeException e) {
      decimalFormats.demote(pattern);
      throw e; // the original's own exception
    }
    // cache only if the hidden inputs didn't change while it was built
    if (locale == Locale.getDefault(Locale.Category.FORMAT) && currency == currency(locale)) {
      decimalFormats.put(pattern, new DecimalFormatPrototype(locale, currency, format));
      return (DecimalFormat) format.clone();
    }
    return format;
  }

  /**
   * The currency that {@code DecimalFormatSymbols} picks for the locale right now, or {@code null}
   * if the country has no currency data.
   */
  private static Currency currency(Locale locale) {
    if (locale.getCountry().isEmpty()) {
      return NO_COUNTRY_CURRENCY;
    }
    try {
      return Currency.getInstance(locale);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** A pristine {@code DecimalFormat}, with the hidden inputs it was built from. */
  private static final class DecimalFormatPrototype {
    final Locale locale;
    final Currency currency;
    final DecimalFormat format;

    DecimalFormatPrototype(Locale locale, Currency currency, DecimalFormat format) {
      this.locale = locale;
      this.currency = currency;
      this.format = format;
    }
  }

  /**
   * {@link URLEncoder#encode(String, String)}. Returns {@code s} itself, as the JDK does, when the
   * charset is a standard one and no character needs encoding.
   */
  public static String urlEncode(String s, String enc) throws UnsupportedEncodingException {
    if (null != s && isStandardCharset(enc) && !needsUrlEncoding(s)) {
      return s;
    }
    return URLEncoder.encode(s, enc);
  }

  /**
   * {@link URLDecoder#decode(String, String)}. Returns {@code s} itself, as the JDK does, when the
   * charset is a standard one and there is nothing to decode.
   */
  public static String urlDecode(String s, String enc) throws UnsupportedEncodingException {
    if (null != s && isStandardCharset(enc) && s.indexOf('%') < 0 && s.indexOf('+') < 0) {
      return s;
    }
    return URLDecoder.decode(s, enc);
  }

  /** Whether URLEncoder changes the char: anything but {@code a-zA-Z0-9-_.*}; space becomes +. */
  private static boolean needsUrlEncoding(String s) {
    for (int i = 0, length = s.length(); i < length; i++) {
      char c = s.charAt(i);
      if (!((c >= 'a' && c <= 'z')
          || (c >= 'A' && c <= 'Z')
          || (c >= '0' && c <= '9')
          || c == '-'
          || c == '_'
          || c == '.'
          || c == '*')) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the name is a charset every JDK supports. Matches ASCII case only: {@code
   * String.equalsIgnoreCase} would also accept non-ASCII letters (e.g. a dotless i), which charset
   * names reject.
   */
  static boolean isStandardCharset(String enc) {
    return null != enc
        && (asciiEqualsIgnoreCase(enc, "utf-8")
            || asciiEqualsIgnoreCase(enc, "iso-8859-1")
            || asciiEqualsIgnoreCase(enc, "us-ascii"));
  }

  private static boolean asciiEqualsIgnoreCase(String s, String lowerCase) {
    int length = s.length();
    if (length != lowerCase.length()) {
      return false;
    }
    for (int i = 0; i < length; i++) {
      char c = s.charAt(i);
      if (c >= 'A' && c <= 'Z') {
        c = (char) (c + ('a' - 'A'));
      }
      if (c != lowerCase.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  /** For tests: whether a formatter is cached for the pattern, without counting a use. */
  static boolean isFormatterCached(String pattern) {
    return formatters.peek(pattern) instanceof DateTimeFormatter;
  }

  /** For tests: whether a decimal format prototype is cached, without counting a use. */
  static boolean isDecimalFormatCached(String pattern) {
    return decimalFormats.peek(pattern) instanceof DecimalFormatPrototype;
  }

  /** For tests. */
  static void clear() {
    formatters.clear();
    decimalFormats.clear();
    PatternCache.clear();
  }
}
