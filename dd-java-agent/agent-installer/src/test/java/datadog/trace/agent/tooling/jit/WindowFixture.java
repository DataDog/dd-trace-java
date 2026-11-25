package datadog.trace.agent.tooling.jit;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Call shapes for the slotted and window rewrites; each method is compared before and after. */
public final class WindowFixture {
  private WindowFixture() {}

  public static String shapeFormatConstant(String s, int i) {
    return String.format("%s-%d", s, i);
  }

  public static String shapeFormatDynamic(String format, String s) {
    return String.format(format, s);
  }

  public static String shapeFormatLocale(String s, int i) {
    return String.format(Locale.FRANCE, "%s:%d", s, i);
  }

  public static String shapeFormatInLoop(String s, int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append(String.format("%s%d;", s, i));
    }
    return sb.toString();
  }

  public static String shapeFormatInTry(String s) {
    try {
      return String.format("[%s]", s);
    } catch (RuntimeException e) {
      return "caught";
    }
  }

  public static boolean shapeCompileMatches(String s) {
    return Pattern.compile("a+b*").matcher(s).matches();
  }

  public static boolean shapeCompileFind(String s) {
    return Pattern.compile("b").matcher(s).find();
  }

  public static boolean shapeCompileLookingAt(String s) {
    return Pattern.compile("a").matcher(s).lookingAt();
  }

  public static String[] shapeCompileSplit(String s) {
    return Pattern.compile(",\\s*").split(s);
  }

  public static boolean shapeCompileMatchesAcrossLines(String s) {
    // the line comments keep the formatter from joining the chain onto one line
    return Pattern.compile("a+") // line 1
        .matcher(s) // line 2
        .matches();
  }

  public static boolean shapeCompileNotASimpleLoad(String s, boolean flag) {
    return Pattern.compile("a").matcher(flag ? s : "x").matches();
  }

  public static boolean shapeCompileInvalid(String s) {
    return Pattern.compile("a(").matcher(s).matches();
  }

  public static String shapeLocalDate(String iso) {
    LocalDate date = LocalDate.parse(iso);
    return date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd EEE"));
  }

  public static String shapeLocalDateTime(String iso) {
    LocalDateTime time = LocalDate.parse(iso).atTime(13, 45);
    return time.format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"));
  }

  public static String shapeFormatterFormat(String iso) {
    LocalDate date = LocalDate.parse(iso);
    return DateTimeFormatter.ofPattern("MMMM d, uuuu").format(date);
  }

  public static String shapeDecimalFormat(double x) {
    return new DecimalFormat("#,##0.00").format(x);
  }

  public static String shapeDecimalFormatDynamic(String pattern, double x) {
    return new DecimalFormat(pattern).format(x);
  }

  public static String shapeUrlEncode(String s) throws Exception {
    return URLEncoder.encode(s, "UTF-8");
  }

  public static String shapeUrlDecode(String s) throws Exception {
    return URLDecoder.decode(s, "UTF-8");
  }

  public static int shapeGetBytes(String s) throws Exception {
    return java.util.Arrays.hashCode(s.getBytes("UTF-8"));
  }

  public static String shapeNewString(String s) throws Exception {
    byte[] bytes = s.getBytes(Charset.forName("ISO-8859-1"));
    return new String(bytes, "UTF-8");
  }

  public static String shapeNewStringRange(String s) throws Exception {
    byte[] bytes = s.getBytes(Charset.forName("UTF-8"));
    return new String(bytes, 1, 2, "US-ASCII");
  }

  public static String shapeNewStringNotSimple(String s) throws Exception {
    return new String(s.getBytes(Charset.forName("UTF-8")), "UTF-8");
  }

  public static String shapeZone(String s) {
    return ZoneId.of("UTC").getId() + ZoneId.of(s).getId();
  }

  public static boolean shapeLowerEquals(String s) {
    return s.toLowerCase().equals("gzip");
  }

  public static boolean shapeLowerEqualsRoot(String s) {
    return s.toLowerCase(Locale.ROOT).equals("content-type");
  }

  public static boolean shapeEqualsLower(String s) {
    return "accept".equals(s.toLowerCase());
  }

  public static boolean shapeUpperStartsWith(String s, String prefix) {
    return s.toUpperCase().startsWith(prefix);
  }

  public static boolean shapeLowerEndsWith(String s) {
    return s.toLowerCase().endsWith(".json");
  }

  public static boolean shapeSubstringStartsWith(String s, int begin) {
    return s.substring(begin).startsWith("ab");
  }

  public static boolean shapeSubstringEndsWith(String s, int begin) {
    return s.substring(begin).endsWith("z");
  }

  public static boolean shapeSubstringRangeEquals(String s, int begin, int end) {
    return s.substring(begin, end).equals("xy");
  }

  public static boolean shapeEqualsSubstring(String s) {
    return "pre".equals(s.substring(0, 3));
  }

  public static boolean shapeTrimIsEmpty(String s) {
    return s.trim().isEmpty();
  }

  public static int shapeTrimLength(String s) {
    return s.trim().length();
  }

  public static String shapeUuid(String s) {
    return UUID.fromString(s).toString();
  }

  public static String shapeSimpleName(String s) {
    return s.getClass().getSimpleName() + WindowFixture.class.getSimpleName();
  }

  public static int shapeParseIntRange(String s) {
    return Integer.parseInt(s.substring(1, 3));
  }

  public static long shapeParseLongFrom(String s) {
    return Long.parseLong(s.substring(2));
  }

  public static Integer shapeValueOfRange(String s) {
    return Integer.valueOf(s.substring(0, 2));
  }

  public static String[] shapeToArrayList(String s) {
    List<String> list = new ArrayList<>(Arrays.asList(s.split(",")));
    return list.toArray(new String[list.size()]);
  }

  public static String[] shapeToArrayArrayList(String s) {
    ArrayList<String> list = new ArrayList<>(Arrays.asList(s.split(",")));
    return list.toArray(new String[list.size()]);
  }

  public static String shapeToArrayCounting(String s) {
    CountingList list = new CountingList(Arrays.asList(s.split(",")));
    String[] array = list.toArray(new String[list.size()]);
    return array.length + ":" + list.sizeCalls;
  }

  /** size() is observable, so it must be called exactly as in the original. */
  public static final class CountingList extends ArrayList<String> {
    public int sizeCalls; // public: the rewritten fixture is defined by another class loader

    public CountingList(List<String> values) {
      super(values);
    }

    @Override
    public int size() {
      sizeCalls++;
      return super.size();
    }
  }
}
