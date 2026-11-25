package datadog.trace.agent.tooling.jit;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/** Call shapes for the slotted and window rewrites; each method is compared before and after. */
public final class WindowFixture {
  private WindowFixture() {}

  public static String formatConstant(String s, int i) {
    return String.format("%s-%d", s, i);
  }

  public static String formatDynamic(String format, String s) {
    return String.format(format, s);
  }

  public static String formatLocale(String s, int i) {
    return String.format(Locale.FRANCE, "%s:%d", s, i);
  }

  public static String formatInLoop(String s, int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append(String.format("%s%d;", s, i));
    }
    return sb.toString();
  }

  public static String formatInTry(String s) {
    try {
      return String.format("[%s]", s);
    } catch (RuntimeException e) {
      return "caught";
    }
  }

  public static boolean compileMatches(String s) {
    return Pattern.compile("a+b*").matcher(s).matches();
  }

  public static boolean compileFind(String s) {
    return Pattern.compile("b").matcher(s).find();
  }

  public static boolean compileLookingAt(String s) {
    return Pattern.compile("a").matcher(s).lookingAt();
  }

  public static String[] compileSplit(String s) {
    return Pattern.compile(",\\s*").split(s);
  }

  public static boolean compileMatchesAcrossLines(String s) {
    // the line comments keep the formatter from joining the chain onto one line
    return Pattern.compile("a+") // line 1
        .matcher(s) // line 2
        .matches();
  }

  public static boolean compileNotASimpleLoad(String s, boolean flag) {
    return Pattern.compile("a").matcher(flag ? s : "x").matches();
  }

  public static boolean compileInvalid(String s) {
    return Pattern.compile("a(").matcher(s).matches();
  }

  public static String localDate(String iso) {
    LocalDate date = LocalDate.parse(iso);
    return date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd EEE"));
  }

  public static String localDateTime(String iso) {
    LocalDateTime time = LocalDate.parse(iso).atTime(13, 45);
    return time.format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"));
  }

  public static String formatterFormat(String iso) {
    LocalDate date = LocalDate.parse(iso);
    return DateTimeFormatter.ofPattern("MMMM d, uuuu").format(date);
  }

  public static String decimalFormat(double x) {
    return new DecimalFormat("#,##0.00").format(x);
  }

  public static String decimalFormatDynamic(String pattern, double x) {
    return new DecimalFormat(pattern).format(x);
  }

  public static String urlEncode(String s) throws Exception {
    return URLEncoder.encode(s, "UTF-8");
  }

  public static String urlDecode(String s) throws Exception {
    return URLDecoder.decode(s, "UTF-8");
  }
}
