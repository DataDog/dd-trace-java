package foo.bar;

import java.util.Arrays;
import java.util.regex.Pattern;

public final class RegexWorkload {

  private RegexWorkload() {}

  public static String run(final int iterations) {
    final StringBuilder out = new StringBuilder();
    for (int i = 0; i < iterations; i++) {
      out.append(Pattern.compile("a+b").matcher("xaaab").find()).append('|');
      out.append(Pattern.compile("A+B", Pattern.CASE_INSENSITIVE).matcher("xaab").find())
          .append('|');
      out.append(Pattern.matches("[0-9]+", "12345")).append('|');
      out.append("hello world".matches("h.*d")).append('|');
      out.append("a1b22c333".replaceAll("[0-9]+", "#")).append('|');
      out.append("a1b22c333".replaceFirst("[0-9]+", "#")).append('|');
      out.append(Arrays.toString("a, b;c  d".split("[,;\\s]+"))).append('|');
      out.append(Arrays.toString("a,b,c,d".split(","))).append('|');
      out.append(Arrays.toString("a1b2c3d".split("[0-9]", 2))).append('|');
      out.append(Arrays.toString("a.b.c".split("\\.", -1))).append('\n');
    }
    return out.toString();
  }

  public static String compile(final String regex) {
    return Pattern.compile(regex).pattern();
  }

  public static String compile(final String regex, final int flags) {
    return Pattern.compile(regex, flags).pattern();
  }

  public static boolean patternMatches(final String regex, final CharSequence input) {
    return Pattern.matches(regex, input);
  }

  public static boolean matches(final String self, final String regex) {
    return self.matches(regex);
  }

  public static String replaceAll(final String self, final String regex, final String repl) {
    return self.replaceAll(regex, repl);
  }

  public static String replaceFirst(final String self, final String regex, final String repl) {
    return self.replaceFirst(regex, repl);
  }

  public static String[] split(final String self, final String regex) {
    return self.split(regex);
  }

  public static String[] split(final String self, final String regex, final int limit) {
    return self.split(regex, limit);
  }
}
