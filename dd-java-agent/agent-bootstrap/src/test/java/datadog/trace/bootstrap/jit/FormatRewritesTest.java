package datadog.trace.bootstrap.jit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Formattable;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Differential tests: every helper must return what the JDK returns, throw what it throws, and call
 * each argument's {@code toString()}/{@code formatTo()} in the same order, cold and warm.
 */
class FormatRewritesTest {

  private static final String[] SUPPORTED_FORMATS = {
    "",
    "plain text",
    "%s",
    "%d",
    "%n",
    "%%",
    "%s-%d",
    "user %s logged in from %s",
    "%d items",
    "line one%nline two %s",
    "100%%",
    "%%%s%%",
    "%s%s%s%s",
    "%d%d",
    "%%d",
    "%%s",
    "x%%%%y",
    "%n%n",
    "ünïcödé %s ✓ 😀",
    "%s\u0000%d",
    " %d %s %d ",
    "%d %d %d %d %d %d",
  };

  private static final String[] UNSUPPORTED_FORMATS = {
    "%",
    "abc%",
    "%s %",
    "%q",
    "%1$s",
    "%2$s %1$s",
    "%<s",
    "%s %<s",
    "%-5d",
    "%,d",
    "%5s",
    "%.2s",
    "%-s",
    "%0d",
    "%+d",
    "% d",
    "%(d",
    "%#s",
    "%S",
    "%D",
    "%x",
    "%o",
    "%b",
    "%c",
    "%f",
    "%.2f",
    "%e",
    "%tY",
    "%s %q",
    "%-%",
    "%5%",
    "%-n",
    "%5n",
  };

  /** Unsupported, but not compared: {@code %h} prints identity hash codes, which differ per run. */
  private static final String[] PARSE_ONLY_FORMATS = {"%h", "%H", "a %h b"};

  private static final String[] LOCALE_TAGS = {
    "und", // Locale.ROOT
    "en-US",
    "fr-FR",
    "de-DE",
    "ar",
    "ar-EG",
    "ar-SA",
    "fa",
    "fa-IR",
    "hi-IN",
    "th-TH-u-nu-thai",
    "hi-IN-u-nu-deva",
    "ja-JP-u-ca-japanese",
    "my",
    "my-MM",
    "bn-BD",
    "mr-IN",
    "ne-NP",
    "ps-AF",
    "ur-IN",
  };

  private static final Random RANDOM = new Random(1234);

  private Locale savedDefault;
  private Locale savedFormatDefault;

  @BeforeEach
  void saveLocaleAndClearCache() {
    savedDefault = Locale.getDefault();
    savedFormatDefault = Locale.getDefault(Locale.Category.FORMAT);
    FormatCache.clear();
  }

  @AfterEach
  void restoreLocale() {
    Locale.setDefault(savedDefault);
    Locale.setDefault(Locale.Category.FORMAT, savedFormatDefault);
  }

  static Stream<Arguments> locales() {
    List<Arguments> locales = new ArrayList<>();
    for (String tag : LOCALE_TAGS) {
      locales.add(Arguments.of(tag, Locale.forLanguageTag(tag)));
    }
    locales.add(Arguments.of("th_TH_TH (legacy Thai digits)", new Locale("th", "TH", "TH")));
    locales.add(Arguments.of("ja_JP_JP (legacy imperial calendar)", new Locale("ja", "JP", "JP")));
    locales.add(Arguments.of("null", null));
    return locales.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("locales")
  void matchesJdkColdAndWarm(String name, Locale locale) {
    if (null != locale) {
      Locale.setDefault(Locale.Category.FORMAT, locale);
    }
    for (String format : allFormats()) {
      boolean supported = CompiledFormat.parse(format) != null;
      List<ArgsFactory> vectors = vectorsFor(format);

      FormatSite[] defaultSites = new FormatSite[1];
      FormatSite[] localeSites = new FormatSite[3];
      FormatCache.clear();
      for (ArgsFactory vector : vectors) {
        if (null != locale) {
          compare(
              format + " (default locale, slotted)",
              vector,
              args -> String.format(format, args),
              args -> FormatRewrites.format(defaultSites, 0, format, args));
          compare(
              format + " (default locale, unslotted)",
              vector,
              args -> String.format(format, args),
              args -> FormatRewrites.format(format, args));
        }
        compare(
            format + " (explicit locale, slotted)",
            vector,
            args -> String.format(locale, format, args),
            args -> FormatRewrites.format(localeSites, 2, locale, format, args));
        compare(
            format + " (explicit locale, unslotted)",
            vector,
            args -> String.format(locale, format, args),
            args -> FormatRewrites.format(locale, format, args));
      }
      // the loop ran well past the threshold: supported formats must now be compiled
      FormatSite site = localeSites[2];
      assertNotNull(site, format);
      if (supported) {
        assertNotNull(site.compiled, format);
        assertSame(format, site.compiled.source, format);
        if (null != locale) {
          assertNotNull(defaultSites[0].compiled, format);
        }
      } else {
        assertNull(site.compiled, format);
        assertTrue(site.disabled, format);
      }
      assertTrue(FormatCache.isCached(format), format);
      assertEquals(supported, FormatCache.get(format) != null, format);
    }
  }

  @Test
  void parseRejectsEverythingButTheSubset() {
    for (String format : SUPPORTED_FORMATS) {
      assertNotNull(CompiledFormat.parse(format), format);
    }
    for (String format : UNSUPPORTED_FORMATS) {
      assertNull(CompiledFormat.parse(format), format);
    }
    for (String format : PARSE_ONLY_FORMATS) {
      assertNull(CompiledFormat.parse(format), format);
    }
    assertNull(CompiledFormat.parse(null));
  }

  @Test
  void nullAndOddArgumentsMatchJdk() {
    String[] formats = {"%s", "%d", "plain", "%s %d", null};
    for (String format : formats) {
      FormatSite[] sites = new FormatSite[1];
      for (int i = 0; i < 3 * FormatSite.COMPILE_THRESHOLD; i++) {
        Object[][] argArrays = {
          null, new Object[0], new Object[] {null}, new Object[] {"a", 1}, new Object[] {1, "a"}
        };
        for (Object[] args : argArrays) {
          compare(
              format,
              log -> args,
              a -> String.format(format, a),
              a -> FormatRewrites.format(sites, 0, format, a));
          compare(
              format,
              log -> args,
              a -> String.format(Locale.FRANCE, format, a),
              a -> FormatRewrites.format(Locale.FRANCE, format, a));
        }
      }
    }
  }

  @Test
  void resultIsANewStringEveryCall() {
    String[] formats = {"plain text", "", "%s", "%%"};
    for (String format : formats) {
      FormatSite[] sites = new FormatSite[1];
      String previous = null;
      for (int i = 0; i < 3 * FormatSite.COMPILE_THRESHOLD; i++) {
        String argument = "x";
        String expected = String.format(format, argument);
        String result = FormatRewrites.format(sites, 0, format, new Object[] {argument});
        assertEquals(expected, result);
        if (!expected.isEmpty()) {
          assertNotSame(format, result);
          assertNotSame(argument, result);
          assertNotSame(previous, result);
        }
        previous = result;
      }
      assertNotNull(sites[0].compiled);
    }
  }

  @Test
  void siteSeeingManyFormatsGoesMegamorphic() {
    FormatSite[] sites = new FormatSite[1];
    List<String> formats = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      formats.add("value " + i + ": %s / %d");
    }
    for (int round = 0; round < 3; round++) {
      for (String format : formats) {
        for (int i = 0; i < FormatSite.COMPILE_THRESHOLD + 3; i++) {
          Object[] args = {"v" + i, i - 5};
          assertEquals(String.format(format, args), FormatRewrites.format(sites, 0, format, args));
        }
      }
    }
    assertTrue(sites[0].disabled);
    assertNull(sites[0].compiled);
  }

  @Test
  void alternatingFormatsStayCorrect() {
    FormatSite[] sites = new FormatSite[1];
    String[] formats = {"a %s", "b %d", "%q", "c %s"};
    for (int i = 0; i < 500; i++) {
      String format = formats[(i / 7) % formats.length];
      Object[] args = {i};
      compare(
          format,
          log -> args,
          a -> String.format(format, a),
          a -> FormatRewrites.format(sites, 0, format, a));
    }
  }

  @Test
  void equalButNotIdenticalFormatUsesTheCompiledFormat() {
    FormatSite[] sites = new FormatSite[1];
    for (int i = 0; i < 3 * FormatSite.COMPILE_THRESHOLD; i++) {
      String format = new String("n=%d");
      assertEquals(
          String.format(format, i), FormatRewrites.format(sites, 0, format, new Object[] {i}));
    }
    assertNotNull(sites[0].compiled);
    assertFalse(sites[0].disabled);
  }

  @Test
  void defaultLocaleChangesWhileWarm() {
    FormatSite[] sites = new FormatSite[1];
    String format = "%s has %d items (%d)";
    Locale[] locales = new Locale[LOCALE_TAGS.length + 1];
    for (int i = 0; i < LOCALE_TAGS.length; i++) {
      locales[i] = Locale.forLanguageTag(LOCALE_TAGS[i]);
    }
    locales[LOCALE_TAGS.length] = new Locale("th", "TH", "TH");
    Locale.setDefault(Locale.Category.FORMAT, Locale.US);
    for (int i = 0; i < 2 * FormatSite.COMPILE_THRESHOLD; i++) {
      FormatRewrites.format(sites, 0, format, new Object[] {"warm", i, -i});
      FormatRewrites.format(format, new Object[] {"warm", i, -i});
    }
    CompiledFormat compiled = sites[0].compiled;
    assertNotNull(compiled);
    assertTrue(FormatCache.isCached(format));
    for (int round = 0; round < 3; round++) {
      for (Locale locale : locales) {
        Locale.setDefault(Locale.Category.FORMAT, locale);
        for (long value : new long[] {0, 7, -1234567890, Long.MIN_VALUE, Long.MAX_VALUE}) {
          Object[] args = {"x", value, (int) value};
          String expected = String.format(format, args);
          assertEquals(expected, FormatRewrites.format(sites, 0, format, args), locale.toString());
          assertEquals(expected, FormatRewrites.format(format, args), locale.toString());
        }
      }
    }
    assertSame(compiled, sites[0].compiled);
  }

  @Test
  void zeroDigitMatchesDecimalFormatSymbols() {
    boolean sawLocalizedDigits = false;
    for (String tag : LOCALE_TAGS) {
      Locale locale = Locale.forLanguageTag(tag);
      char expected = DecimalFormatSymbols.getInstance(locale).getZeroDigit();
      // twice: the lookup, then the cached value
      assertEquals(expected, CompiledFormat.zeroDigit(locale), tag);
      assertEquals(expected, CompiledFormat.zeroDigit(locale), tag);
      sawLocalizedDigits |= expected != '0';
      System.out.println(
          "java.version="
              + System.getProperty("java.version")
              + " "
              + tag
              + " -> "
              + String.format(locale, "%d", -1234567890));
    }
    assertEquals('0', CompiledFormat.zeroDigit(null));
    assertTrue(sawLocalizedDigits, "no test locale exercises non-ASCII digits on this JDK");
  }

  @Test
  void argumentChangedByEarlierToStringMatchesJdk() {
    Object[] replacements = {
      null,
      42,
      -7L,
      (short) 3,
      (byte) -1,
      "str",
      1.5d,
      new BigInteger("-12345678901234567890"),
      new ClosingFormattable(new ArrayList<String>()),
    };
    String[] formats = {
      "%s %s", "%s %d", "%s %d %s", "%s:%s%n%%%s", "%s%s", "%s%d%%", "%s %s%n", "%s %d %d %s %s"
    };
    for (Locale locale : new Locale[] {Locale.US, Locale.forLanguageTag("th-TH-u-nu-thai")}) {
      for (String format : formats) {
        CompiledFormat compiled = CompiledFormat.parse(format);
        FormatSite[] sites = new FormatSite[1];
        for (int r = 0; r <= replacements.length; r++) {
          final int replacement = r;
          ArgsFactory vector =
              log -> {
                Object[] args = new Object[5];
                args[0] = new Mutator(log, args, 1, replacement, replacements);
                args[1] = 5;
                args[2] = 8;
                args[3] = new Mutator(log, args, 4, replacement, replacements);
                args[4] = new Recording(log, "e");
                return args;
              };
          for (int i = 0; i < 2 * FormatSite.COMPILE_THRESHOLD; i++) {
            compare(
                format,
                vector,
                args -> String.format(locale, format, args),
                args -> FormatRewrites.format(sites, 0, locale, format, args));
          }
          // straight into the compiled format, where the change is noticed mid-way
          compare(
              format,
              vector,
              args -> String.format(locale, format, args),
              args -> {
                String result = compiled.apply(locale, args);
                assertNotNull(result, "the arguments fit before the change");
                return result;
              });
        }
      }
    }
  }

  @Test
  void formattedMatchesJdk() throws Exception {
    Method formatted;
    try {
      formatted = String.class.getMethod("formatted", Object[].class);
    } catch (NoSuchMethodException e) {
      assumeTrue(false, "String.formatted needs JDK 15+");
      return;
    }
    for (String format : allFormats()) {
      FormatCache.clear();
      for (ArgsFactory vector : vectorsFor(format)) {
        compare(
            format,
            vector,
            args -> {
              try {
                return (String) formatted.invoke(format, new Object[] {args});
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            },
            args -> FormatRewrites.formatted(format, args));
      }
    }
  }

  @Test
  void formattedWithNullFormatThrowsNullPointerException() {
    Class<? extends Throwable> thrown = null;
    try {
      FormatRewrites.formatted(null, new Object[] {1});
    } catch (Throwable e) {
      thrown = e.getClass();
    }
    // the message can't match: for a null receiver, it describes the caller's bytecode
    assertEquals(NullPointerException.class, thrown);
  }

  @Test
  void coldFormatsAreNotRetained() {
    String format = "cold %s";
    for (int i = 0; i < FormatCache.PROMOTION_THRESHOLD - 1; i++) {
      FormatRewrites.format(format, new Object[] {i});
    }
    assertFalse(FormatCache.isCached(format));
    FormatRewrites.format(format, new Object[] {"last"});
    assertTrue(FormatCache.isCached(format));
  }

  @Test
  void cacheStopsGrowingWhenFull() {
    for (int i = 0; i < FormatCache.MAX_ENTRIES + 50; i++) {
      String format = "format " + i + " %s";
      for (int j = 0; j < FormatCache.PROMOTION_THRESHOLD; j++) {
        Object[] args = {j};
        assertEquals(String.format(format, args), FormatRewrites.format(format, args));
      }
    }
    assertFalse(FormatCache.isCached("format " + (FormatCache.MAX_ENTRIES + 49) + " %s"));
  }

  // ---------------------------------------------------------------------------------------------

  private static List<String> allFormats() {
    List<String> formats = new ArrayList<>(Arrays.asList(SUPPORTED_FORMATS));
    formats.addAll(Arrays.asList(UNSUPPORTED_FORMATS));
    return formats;
  }

  /** Builds a fresh argument array whose objects record into the given log. */
  interface ArgsFactory {
    Object[] create(List<String> log);
  }

  interface Call {
    String call(Object[] args) throws Throwable;
  }

  /** Runs both, with fresh arguments and logs, and compares results, exceptions and logs. */
  private static void compare(String description, ArgsFactory vector, Call jdk, Call helper) {
    List<String> jdkLog = new ArrayList<>();
    List<String> helperLog = new ArrayList<>();
    Object[] jdkArgs = vector.create(jdkLog);
    Object[] helperArgs = vector.create(helperLog);
    String expected = outcome(jdk, jdkArgs);
    String actual = outcome(helper, helperArgs);
    String context = description + " " + describeArgs(vector);
    if (expected.startsWith(FAST_THROW) || actual.startsWith(FAST_THROW)) {
      // the JIT may throw a preallocated exception, without message, from any hot JDK frame
      expected = thrownClass(expected);
      actual = thrownClass(actual);
    }
    assertEquals(expected, actual, context);
    assertEquals(jdkLog, helperLog, context);
  }

  private static final String FAST_THROW = "threw (fast)";

  private static String outcome(Call call, Object[] args) {
    try {
      return "returned: " + call.call(args);
    } catch (Throwable e) {
      boolean fastThrow = null == e.getMessage() && e.getStackTrace().length == 0;
      return (fastThrow ? FAST_THROW : "threw")
          + ": "
          + e.getClass().getName()
          + ": "
          + e.getMessage();
    }
  }

  private static String thrownClass(String outcome) {
    if (!outcome.startsWith("threw")) {
      return outcome;
    }
    int start = outcome.indexOf(": ") + 2;
    return "threw " + outcome.substring(start, outcome.indexOf(": ", start));
  }

  private static String describeArgs(ArgsFactory vector) {
    Object[] args = vector.create(new ArrayList<String>());
    if (null == args) {
      return "args=null";
    }
    StringBuilder sb = new StringBuilder("args=[");
    for (Object arg : args) {
      sb.append(null == arg ? "null" : arg.getClass().getSimpleName()).append(' ');
    }
    return sb.append(']').toString();
  }

  /** Argument vectors: arguments that fit, random ones, and odd arrays. */
  private static List<ArgsFactory> vectorsFor(String format) {
    String conversions = conversionsOf(format);
    List<ArgsFactory> vectors = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      vectors.add(randomVector(conversions.length() + 2));
    }
    for (int i = 0; i < FormatSite.COMPILE_THRESHOLD + 4; i++) {
      vectors.add(fittingVector(conversions, i));
    }
    for (int i = 0; i < 20; i++) {
      vectors.add(randomVector(conversions.length() + 2));
      vectors.add(fittingVector(conversions, i + 100));
    }
    vectors.add(log -> null);
    vectors.add(log -> new Object[0]);
    if (conversions.length() > 0) {
      int tooFew = conversions.length() - 1;
      vectors.add(log -> recordingArgs(log, tooFew));
    }
    int extra = conversions.length() + 2;
    vectors.add(log -> recordingArgs(log, extra));
    return vectors;
  }

  /** The 's'/'d' conversions of a supported format, in order (best effort for others). */
  private static String conversionsOf(String format) {
    StringBuilder conversions = new StringBuilder();
    for (int i = 0; i + 1 < format.length(); i++) {
      if (format.charAt(i) == '%') {
        char c = format.charAt(++i);
        if (c == 's' || c == 'd') {
          conversions.append(c);
        } else if (c != '%' && c != 'n') {
          conversions.append('s');
        }
      }
    }
    return conversions.toString();
  }

  private static Object[] recordingArgs(List<String> log, int count) {
    Object[] args = new Object[count];
    for (int i = 0; i < count; i++) {
      args[i] = new Recording(log, "r" + i);
    }
    return args;
  }

  private static final int STRING_KINDS = 8;
  private static final int DECIMAL_KINDS = 16;

  private static ArgsFactory fittingVector(String conversions, int seed) {
    int[] picks = new int[conversions.length()];
    for (int i = 0; i < picks.length; i++) {
      picks[i] =
          conversions.charAt(i) == 's'
              ? RANDOM.nextInt(STRING_KINDS)
              : STRING_KINDS + RANDOM.nextInt(DECIMAL_KINDS);
    }
    return log -> {
      Object[] args = new Object[picks.length];
      for (int i = 0; i < picks.length; i++) {
        args[i] = argument(picks[i], log, i + seed);
      }
      return args;
    };
  }

  private static ArgsFactory randomVector(int maxLength) {
    int[] picks = new int[RANDOM.nextInt(maxLength + 1)];
    for (int i = 0; i < picks.length; i++) {
      picks[i] = RANDOM.nextInt(ARGUMENT_KINDS);
    }
    return log -> {
      Object[] args = new Object[picks.length];
      for (int i = 0; i < picks.length; i++) {
        args[i] = argument(picks[i], log, i);
      }
      return args;
    };
  }

  private static final int ARGUMENT_KINDS = STRING_KINDS + DECIMAL_KINDS + 9;

  /** Kinds [0, 8) fit %s; [8, 24) fit %d; the rest fit neither or need the JDK. */
  private static Object argument(int kind, List<String> log, int n) {
    switch (kind) {
      // fit %s
      case 0:
        return null;
      case 1:
        return "str" + n;
      case 2:
        return "";
      case 3:
        return new Recording(log, "rec" + n);
      case 4:
        return new NullToString(log);
      case 5:
        return n;
      case 6:
        return new StringBuilder("sb").append(n);
      case 7:
        return 'c';
      // fit %d
      case 8:
        return null;
      case 9:
        return 0;
      case 10:
        return n - 3;
      case 11:
        return Integer.MIN_VALUE;
      case 12:
        return Integer.MAX_VALUE;
      case 13:
        return Long.MIN_VALUE;
      case 14:
        return Long.MAX_VALUE;
      case 15:
        return -1234567890123L;
      case 16:
        return Short.MIN_VALUE;
      case 17:
        return Short.MAX_VALUE;
      case 18:
        return Byte.MIN_VALUE;
      case 19:
        return Byte.MAX_VALUE;
      case 20:
        return (short) -n;
      case 21:
        return (byte) n;
      case 22:
        return 9876543210L;
      case 23:
        return -1;
      // fit neither, or need the JDK
      case 24:
        return new BigInteger("-98765432109876543210");
      case 25:
        return 1.5d;
      case 26:
        return new RecordingFormattable(log, "fmt" + n);
      case 27:
        return new Throwing(log, "boom" + n);
      case 28:
        return Boolean.TRUE;
      case 29:
        return 2.5f;
      case 30:
        return Arrays.asList(1, "two", null);
      case 31:
        return new ClosingFormattable(log);
      default:
        return new java.util.concurrent.atomic.AtomicInteger(n);
    }
  }

  /** Records each toString() call. */
  static final class Recording {
    private final List<String> log;
    private final String name;

    Recording(List<String> log, String name) {
      this.log = log;
      this.name = name;
    }

    @Override
    public String toString() {
      log.add("toString " + name);
      return name;
    }
  }

  static final class NullToString {
    private final List<String> log;

    NullToString(List<String> log) {
      this.log = log;
    }

    @Override
    public String toString() {
      log.add("toString null");
      return null;
    }
  }

  static final class Throwing {
    private final List<String> log;
    private final String name;

    Throwing(List<String> log, String name) {
      this.log = log;
      this.name = name;
    }

    @Override
    public String toString() {
      log.add("toString throws " + name);
      throw new IllegalStateException(name);
    }
  }

  /** Records each formatTo() call, with what the formatter shows it. */
  static final class RecordingFormattable implements Formattable {
    private final List<String> log;
    private final String name;

    RecordingFormattable(List<String> log, String name) {
      this.log = log;
      this.name = name;
    }

    @Override
    public void formatTo(Formatter formatter, int flags, int width, int precision) {
      log.add(
          "formatTo "
              + name
              + " flags="
              + flags
              + " width="
              + width
              + " precision="
              + precision
              + " locale="
              + formatter.locale()
              + " out="
              + formatter.out());
      formatter.format("<%s>", name);
    }

    @Override
    public String toString() {
      log.add("toString " + name);
      return name;
    }
  }

  /** Closes the formatter it's given, after writing to it. */
  static final class ClosingFormattable implements Formattable {
    private final List<String> log;

    ClosingFormattable(List<String> log) {
      this.log = log;
    }

    @Override
    public void formatTo(Formatter formatter, int flags, int width, int precision) {
      log.add("formatTo closing out=" + formatter.out() + " text=" + formatter);
      formatter.format("[%d|%s]", 12, "x");
      formatter.close();
    }

    @Override
    public int hashCode() {
      return 1;
    }
  }

  /** Replaces another argument in the array from its toString(). */
  static final class Mutator {
    private final List<String> log;
    private final Object[] args;
    private final int index;
    private final int replacement;
    private final Object[] replacements;

    Mutator(List<String> log, Object[] args, int index, int replacement, Object[] replacements) {
      this.log = log;
      this.args = args;
      this.index = index;
      this.replacement = replacement;
      this.replacements = replacements;
    }

    @Override
    public String toString() {
      log.add("toString mutator");
      args[index] =
          replacement < replacements.length
              ? replacements[replacement]
              : new RecordingFormattable(log, "late");
      return "mutator";
    }
  }
}
