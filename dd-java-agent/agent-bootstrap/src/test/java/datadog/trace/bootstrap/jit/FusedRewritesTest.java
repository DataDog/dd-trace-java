package datadog.trace.bootstrap.jit;

import static datadog.trace.bootstrap.jit.Outcomes.assertSameOutcome;
import static datadog.trace.bootstrap.jit.Outcomes.outcome;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.AttributedCharacterIterator;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParsePosition;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Differential tests: every helper must behave exactly like the code sequence it replaces. */
class FusedRewritesTest {

  /** Default FORMAT locales to test under, including ones with non-Latin digits and calendars. */
  private static final Locale[] LOCALES = {
    Locale.US,
    Locale.forLanguageTag("ar"),
    Locale.forLanguageTag("ar-EG"),
    Locale.forLanguageTag("hi-IN"),
    Locale.forLanguageTag("th-TH-u-nu-thai"),
    Locale.forLanguageTag("ja-JP-u-ca-japanese"),
    Locale.forLanguageTag("fr-FR"),
    Locale.forLanguageTag("de-CH"),
    Locale.forLanguageTag("en-US-u-cu-eur"),
    Locale.forLanguageTag("en-001"),
    Locale.ROOT,
  };

  private static final int WARM_UP = TieredCache.PROMOTION_THRESHOLD + 1;

  private Locale savedDefault;
  private Locale savedFormat;

  @BeforeEach
  void saveDefaults() {
    savedDefault = Locale.getDefault();
    savedFormat = Locale.getDefault(Locale.Category.FORMAT);
    FusedRewrites.clear();
  }

  @AfterEach
  void restoreDefaults() {
    Locale.setDefault(savedDefault);
    Locale.setDefault(Locale.Category.FORMAT, savedFormat);
    FusedRewrites.clear();
  }

  // --- Pattern.compile(regex).matcher(input).xxx() ---

  private static final String[] REGEXES = {
    "a+b",
    "\\s*,\\s*",
    "[a-z]+\\d+",
    "(?i)ab",
    "^a",
    "b$",
    "",
    "a|",
    "\\uD800",
    "\\uDC00",
    "𐀀",
    "\uDC00",
    "x*",
    "(a)(b)?",
    "[",
    "\\",
    "(",
    "a{",
    "*",
    null,
  };

  private static final String[] REGEX_INPUTS = {
    "", "a", "ab", "aab", "AB", "a,b , c", "abc123", "ba", "𐀀", "a\uDC00b", "xxx", null,
  };

  @Test
  void compileHelpersMatchJdk() {
    for (int pass = 0; pass < 3; pass++) {
      for (String regex : REGEXES) {
        for (String input : REGEX_INPUTS) {
          checkCompile(regex, input);
          if (null != input) {
            checkCompile(regex, new StringBuilder(input));
          }
        }
      }
    }
    assertTrue(PatternCache.isCached("a+b"));
    assertFalse(PatternCache.isCached("["));
  }

  private static void checkCompile(String regex, CharSequence input) {
    assertSameOutcome(
        () -> Pattern.compile(regex).matcher(input).matches(),
        () -> FusedRewrites.compileMatches(regex, input));
    assertSameOutcome(
        () -> Pattern.compile(regex).matcher(input).find(),
        () -> FusedRewrites.compileFind(regex, input));
    assertSameOutcome(
        () -> Pattern.compile(regex).matcher(input).lookingAt(),
        () -> FusedRewrites.compileLookingAt(regex, input));
    assertSameOutcome(
        () -> Pattern.compile(regex).split(input), () -> FusedRewrites.compileSplit(regex, input));
  }

  /** The input sees the same calls, in the same order, whether the pattern is cached or not. */
  @Test
  void compileHelpersCallTheInputLikeJdk() {
    for (int pass = 0; pass < 3; pass++) {
      for (String regex : new String[] {"a+b", "\\s*,\\s*", "x*"}) {
        for (String input : new String[] {"", "aab", "a , b,c", "xx"}) {
          List<String> expected = new ArrayList<>();
          List<String> actual = new ArrayList<>();
          assertSameOutcome(
              () -> Pattern.compile(regex).split(new Logging(input, expected)),
              () -> FusedRewrites.compileSplit(regex, new Logging(input, actual)));
          assertSameOutcome(
              () -> Pattern.compile(regex).matcher(new Logging(input, expected)).find(),
              () -> FusedRewrites.compileFind(regex, new Logging(input, actual)));
          assertEquals(expected, actual);
        }
      }
    }
  }

  // --- t.format(DateTimeFormatter.ofPattern(pattern)) ---

  private static final String[] DATE_PATTERNS = {
    "yyyy-MM-dd",
    "uuuu-MM-dd'T'HH:mm:ss.SSSXXX",
    "HH:mm:ss",
    "h:mm a",
    "EEEE, MMMM d, yyyy",
    "EEE MMM dd",
    "G yyyy",
    "Y-ww-e",
    "QQQ",
    "z",
    "zzzz",
    "VV",
    "O",
    "xxx",
    "B",
    "n",
    "[yyyy]['T'HH]",
    "''",
    "dd/MM/yy",
    "{",
    "yyyy-MM-dd {",
    "#",
    "ppH",
    "",
    null,
  };

  private static final ZonedDateTime ZONED =
      ZonedDateTime.of(2024, 2, 29, 13, 5, 9, 123_456_789, ZoneId.of("America/New_York"));

  private static final TemporalAccessor[] TEMPORALS = {
    ZONED.toLocalDate(),
    ZONED.toLocalDateTime(),
    ZONED.toLocalTime(),
    ZONED,
    ZONED.withZoneSameInstant(ZoneId.of("Asia/Tokyo")),
    ZONED.toOffsetDateTime(),
    ZONED.toOffsetDateTime().toOffsetTime(),
    LocalDate.of(-50, 1, 1),
    LocalDateTime.of(1989, 1, 7, 0, 0),
    OffsetTime.of(0, 0, 0, 0, ZoneOffset.ofHours(-14)),
    Instant.EPOCH,
  };

  @Test
  void temporalFormatMatchesJdk() {
    for (int pass = 0; pass < 2; pass++) {
      for (Locale locale : LOCALES) {
        // the cache is kept across locales: the cached formatter must follow the default
        Locale.setDefault(Locale.Category.FORMAT, locale);
        for (int warm = 0; warm < 2; warm++) {
          for (String pattern : DATE_PATTERNS) {
            checkTemporalFormat(pattern);
          }
          warmFormatters();
        }
      }
    }
    assertTrue(FusedRewrites.isFormatterCached("yyyy-MM-dd"));
    assertFalse(FusedRewrites.isFormatterCached("{"));
  }

  @Test
  void cachedFormatterFollowsDefaultLocale() {
    Locale.setDefault(Locale.Category.FORMAT, Locale.US);
    warmFormatters();
    LocalDate date = LocalDate.of(2024, 3, 1);
    assertEquals("Friday", FusedRewrites.temporalFormat(date, "EEEE"));
    Locale.setDefault(Locale.Category.FORMAT, Locale.FRANCE);
    assertEquals("vendredi", FusedRewrites.temporalFormat(date, "EEEE"));
    Locale.setDefault(Locale.Category.FORMAT, Locale.US);
    assertEquals("Friday", FusedRewrites.temporalFormat(date, "EEEE"));
  }

  private static void warmFormatters() {
    for (String pattern : DATE_PATTERNS) {
      for (int i = 0; i < WARM_UP; i++) {
        outcome(() -> FusedRewrites.ofPattern(pattern));
      }
    }
    for (int i = 0; i < WARM_UP; i++) {
      FusedRewrites.ofPattern("EEEE");
    }
  }

  private static void checkTemporalFormat(String pattern) {
    for (TemporalAccessor t : TEMPORALS) {
      assertSameOutcome(
          () -> DateTimeFormatter.ofPattern(pattern).format(t),
          () -> FusedRewrites.formatterFormat(pattern, t));
      checkTemporalOverload(pattern, t);
    }
    assertSameOutcome(
        () -> DateTimeFormatter.ofPattern(pattern).format(null),
        () -> FusedRewrites.formatterFormat(pattern, null));
    checkTemporalOverload(pattern, null);
  }

  /** The overload for the temporal's type, or each overload with a null temporal. */
  private static void checkTemporalOverload(String pattern, TemporalAccessor t) {
    if (null == t || t instanceof LocalDate) {
      LocalDate d = (LocalDate) t;
      assertSameOutcome(
          () -> d.format(DateTimeFormatter.ofPattern(pattern)),
          () -> FusedRewrites.temporalFormat(d, pattern));
    }
    if (null == t || t instanceof LocalDateTime) {
      LocalDateTime d = (LocalDateTime) t;
      assertSameOutcome(
          () -> d.format(DateTimeFormatter.ofPattern(pattern)),
          () -> FusedRewrites.temporalFormat(d, pattern));
    }
    if (null == t || t instanceof LocalTime) {
      LocalTime d = (LocalTime) t;
      assertSameOutcome(
          () -> d.format(DateTimeFormatter.ofPattern(pattern)),
          () -> FusedRewrites.temporalFormat(d, pattern));
    }
    if (null == t || t instanceof ZonedDateTime) {
      ZonedDateTime d = (ZonedDateTime) t;
      assertSameOutcome(
          () -> d.format(DateTimeFormatter.ofPattern(pattern)),
          () -> FusedRewrites.temporalFormat(d, pattern));
    }
    if (null == t || t instanceof OffsetDateTime) {
      OffsetDateTime d = (OffsetDateTime) t;
      assertSameOutcome(
          () -> d.format(DateTimeFormatter.ofPattern(pattern)),
          () -> FusedRewrites.temporalFormat(d, pattern));
    }
    if (null == t || t instanceof OffsetTime) {
      OffsetTime d = (OffsetTime) t;
      assertSameOutcome(
          () -> d.format(DateTimeFormatter.ofPattern(pattern)),
          () -> FusedRewrites.temporalFormat(d, pattern));
    }
  }

  // --- new DecimalFormat(pattern) ---

  private static final String[] DECIMAL_PATTERNS = {
    "#,##0.00",
    "0.###E0",
    "##0.#####E0",
    "00.00",
    "#",
    "#%",
    "#‰",
    "¤#,##0.00",
    "¤¤ #,##0.00",
    "#,##0.00;(#,##0.00)",
    "'#'#",
    "#,##,###",
    "0.0#;-0.0#",
    "",
    "#,##0.0#.0",
    "0.0.0",
    "#E0E0",
    "'",
    null,
  };

  private static final Object[] NUMBERS = {
    0,
    -0.0,
    1,
    -1,
    12345,
    Long.MIN_VALUE,
    Long.MAX_VALUE,
    0.5,
    1.005,
    -1234.5678,
    1e-10,
    6.02214076e23,
    Double.NaN,
    Double.POSITIVE_INFINITY,
    Double.NEGATIVE_INFINITY,
    Double.MIN_VALUE,
    new BigDecimal("123456789012345678901234567890.123456789"),
    new BigInteger("-98765432109876543210"),
  };

  @Test
  void newDecimalFormatMatchesJdk() throws IOException {
    for (int pass = 0; pass < 2; pass++) {
      for (Locale locale : LOCALES) {
        Locale.setDefault(Locale.Category.FORMAT, locale);
        for (int warm = 0; warm < 2; warm++) {
          for (String pattern : DECIMAL_PATTERNS) {
            checkDecimalFormat(pattern);
          }
          warmDecimalFormats();
        }
      }
    }
    Locale.setDefault(Locale.Category.FORMAT, Locale.US);
    warmDecimalFormats();
    assertEquals(
        FusedRewrites.CLONES_DECIMAL_FORMAT, FusedRewrites.isDecimalFormatCached("#,##0.00"));
    assertFalse(FusedRewrites.isDecimalFormatCached("0.0.0"));
  }

  /**
   * Why copies are only used on JDK 8: later JDKs start a new DecimalFormatSymbols with currency
   * symbols cached JVM-wide by other instances, so an older copy can serialize differently.
   */
  @Test
  void decimalFormatCopyEquivalenceMatchesTheJdkGate() throws IOException {
    Locale locale = new Locale("sw", "KE", "FUSED" + System.nanoTime()); // a fresh locale cache
    Locale.setDefault(Locale.Category.FORMAT, locale);
    DecimalFormat prototype = new DecimalFormat("#,##0.00");
    new DecimalFormat("#,##0.00").getCurrency(); // sets up the currency, caching its symbols
    DecimalFormat fresh = new DecimalFormat("#,##0.00");
    boolean sameBytes =
        Arrays.equals(serialize(fresh), serialize((DecimalFormat) prototype.clone()));
    assertEquals(FusedRewrites.CLONES_DECIMAL_FORMAT, sameBytes);
  }

  @Test
  void decimalFormatCopiesAreIndependent() {
    Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
    warmDecimalFormats();
    DecimalFormat first = FusedRewrites.newDecimalFormat("#,##0.00");
    DecimalFormat second = FusedRewrites.newDecimalFormat("#,##0.00");
    assertNotSame(first, second);
    assertEquals("1.234,50", first.format(1234.5));
    first.applyPattern("0.0");
    first.setMaximumFractionDigits(7);
    first.setRoundingMode(RoundingMode.UP);
    first.setPositivePrefix("+");
    DecimalFormatSymbols symbols = first.getDecimalFormatSymbols();
    symbols.setDecimalSeparator('!');
    first.setDecimalFormatSymbols(symbols);
    first.format(1.25);
    DecimalFormat third = FusedRewrites.newDecimalFormat("#,##0.00");
    DecimalFormat fresh = new DecimalFormat("#,##0.00");
    assertEquals(fresh, second);
    assertEquals(fresh, third);
    assertEquals("1.234,50", third.format(1234.5));
    Locale.setDefault(Locale.Category.FORMAT, Locale.FRANCE);
    assertEquals(new DecimalFormat("#,##0.00"), FusedRewrites.newDecimalFormat("#,##0.00"));
  }

  @Test
  void decimalFormatFollowsCurrencyOfDefaultLocale() {
    Locale.setDefault(Locale.Category.FORMAT, Locale.US);
    warmDecimalFormats();
    assertEquals("$1.50", FusedRewrites.newDecimalFormat("¤#,##0.00").format(1.5));
    Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("en-US-u-cu-eur"));
    assertEquals(
        new DecimalFormat("¤#,##0.00").format(1.5),
        FusedRewrites.newDecimalFormat("¤#,##0.00").format(1.5));
  }

  private static void warmDecimalFormats() {
    for (String pattern : DECIMAL_PATTERNS) {
      for (int i = 0; i < WARM_UP; i++) {
        outcome(() -> FusedRewrites.newDecimalFormat(pattern));
      }
    }
  }

  private static void checkDecimalFormat(String pattern) throws IOException {
    Object expected = outcome(() -> new DecimalFormat(pattern));
    Object actual = outcome(() -> FusedRewrites.newDecimalFormat(pattern));
    if (!(expected instanceof DecimalFormat)) {
      assertEquals(expected, actual);
      return;
    }
    DecimalFormat fresh = (DecimalFormat) expected;
    DecimalFormat copy = (DecimalFormat) actual;
    assertEquals(DecimalFormat.class, copy.getClass());
    // pristine objects first: serialization and equality before any use
    assertArrayEquals(serialize(fresh), serialize(copy), pattern);
    assertEquals(fresh, copy);
    assertEquals(fresh.hashCode(), copy.hashCode());
    assertEquals(describe(fresh), describe(copy));
    for (Object number : NUMBERS) {
      String formatted = fresh.format(number);
      assertEquals(formatted, copy.format(number));
      assertEquals(attributes(fresh, number), attributes(copy, number));
      assertEquals(parse(fresh, formatted), parse(copy, formatted));
    }
    for (String text : new String[] {"1,234.5", "1.234,5", "-0", "(12)", "1E3", "∞", "abc"}) {
      assertEquals(parse(fresh, text), parse(copy, text));
    }
    fresh.setParseBigDecimal(true);
    copy.setParseBigDecimal(true);
    assertEquals(
        parse(fresh, "123456789012345678901234567890.5"),
        parse(copy, "123456789012345678901234567890.5"));
    // and after use, including the fast-path state
    assertEquals(fresh, copy);
    assertArrayEquals(serialize(fresh), serialize(copy), pattern);
  }

  private static List<Object> describe(DecimalFormat f) {
    DecimalFormatSymbols symbols = f.getDecimalFormatSymbols();
    // the empty pattern allows Integer.MAX_VALUE digits, which toPattern() tries to write out
    boolean printable = f.getMaximumIntegerDigits() < 1000;
    return Arrays.asList(
        printable ? f.toPattern() : null,
        printable ? f.toLocalizedPattern() : null,
        symbols,
        symbols.getCurrency(),
        symbols.getCurrencySymbol(),
        symbols.getInternationalCurrencySymbol(),
        symbols.getZeroDigit(),
        f.getCurrency(),
        f.getPositivePrefix(),
        f.getNegativePrefix(),
        f.getPositiveSuffix(),
        f.getNegativeSuffix(),
        f.getMultiplier(),
        f.getGroupingSize(),
        f.isGroupingUsed(),
        f.isDecimalSeparatorAlwaysShown(),
        f.isParseBigDecimal(),
        f.isParseIntegerOnly(),
        f.getRoundingMode(),
        f.getMinimumIntegerDigits(),
        f.getMaximumIntegerDigits(),
        f.getMinimumFractionDigits(),
        f.getMaximumFractionDigits());
  }

  private static List<Object> attributes(DecimalFormat f, Object number) {
    AttributedCharacterIterator it = f.formatToCharacterIterator(number);
    List<Object> runs = new ArrayList<>();
    for (char c = it.first(); c != AttributedCharacterIterator.DONE; c = it.next()) {
      runs.add(c);
      runs.add(it.getAttributes().keySet().toString());
    }
    return runs;
  }

  private static List<Object> parse(DecimalFormat f, String text) {
    ParsePosition position = new ParsePosition(0);
    Number n = f.parse(text, position);
    return Arrays.asList(
        null == n ? null : n.getClass(), n, position.getIndex(), position.getErrorIndex());
  }

  private static byte[] serialize(Object o) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ObjectOutputStream out = new ObjectOutputStream(bytes);
    out.writeObject(o);
    out.close();
    return bytes.toByteArray();
  }

  // --- URLEncoder.encode / URLDecoder.decode ---

  private static final String[] URL_INPUTS = {
    "",
    "abc",
    "AZaz09-_.*",
    "a b",
    "a+b",
    "a%20b",
    "%",
    "%2",
    "%zz",
    "%-1",
    "~",
    "!'()",
    "café",
    "𐀀",
    "\uD800",
    "\uDC00x",
    "a/b?c=d&e",
    null,
  };

  private static final String[] ENCODINGS = {
    "UTF-8",
    "utf-8",
    "Utf-8",
    "ISO-8859-1",
    "iso-8859-1",
    "US-ASCII",
    "us-ascii",
    "UTF8",
    "UTF-16",
    "ıso-8859-1",
    "uſaſcii",
    "UTF-8 ",
    "",
    "bogus",
    "x y",
    null,
  };

  @Test
  void urlCodecsMatchJdk() {
    for (String s : URL_INPUTS) {
      for (String enc : ENCODINGS) {
        assertSameOutcome(() -> URLEncoder.encode(s, enc), () -> FusedRewrites.urlEncode(s, enc));
        assertSameOutcome(() -> URLDecoder.decode(s, enc), () -> FusedRewrites.urlDecode(s, enc));
        assertEquals(
            outcome(() -> URLEncoder.encode(s, enc) == s),
            outcome(() -> FusedRewrites.urlEncode(s, enc) == s));
        assertEquals(
            outcome(() -> URLDecoder.decode(s, enc) == s),
            outcome(() -> FusedRewrites.urlDecode(s, enc) == s));
      }
    }
  }

  @Test
  void urlCodecsMatchJdkOnRandomInputs() {
    Random random = new Random(3);
    String alphabet = "aZ09-_.*+% ~/é𐀀";
    for (int i = 0; i < 5000; i++) {
      StringBuilder b = new StringBuilder();
      int length = random.nextInt(8);
      for (int j = 0; j < length; j++) {
        b.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      String s = b.toString();
      String enc = ENCODINGS[random.nextInt(ENCODINGS.length)];
      assertSameOutcome(() -> URLEncoder.encode(s, enc), () -> FusedRewrites.urlEncode(s, enc));
      assertSameOutcome(() -> URLDecoder.decode(s, enc), () -> FusedRewrites.urlDecode(s, enc));
    }
  }

  @Test
  void unchangedUrlTextIsReturnedAsIs() throws Exception {
    String s = "abc-_.*";
    assertSame(URLEncoder.encode(s, "UTF-8"), s);
    assertSame(s, FusedRewrites.urlEncode(s, "UTF-8"));
    assertSame(URLDecoder.decode(s, "UTF-8"), s);
    assertSame(s, FusedRewrites.urlDecode(s, "UTF-8"));
    assertFalse(FusedRewrites.isStandardCharset("ıso-8859-1"));
  }

  /** A CharSequence that logs every call. */
  private static final class Logging implements CharSequence {
    private final String value;
    private final List<String> log;

    Logging(String value, List<String> log) {
      this.value = value;
      this.log = log;
    }

    @Override
    public int length() {
      log.add("length");
      return value.length();
    }

    @Override
    public char charAt(int index) {
      log.add("charAt " + index);
      return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      log.add("subSequence " + start + " " + end);
      return value.subSequence(start, end);
    }

    @Override
    public String toString() {
      log.add("toString");
      return value;
    }
  }
}
