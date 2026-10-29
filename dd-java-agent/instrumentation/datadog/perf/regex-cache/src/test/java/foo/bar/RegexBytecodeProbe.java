package foo.bar;

import java.util.regex.Pattern;

public final class RegexBytecodeProbe {

  private RegexBytecodeProbe() {}

  public static Object[] callAll() {
    return new Object[] {
      Pattern.compile("a+"),
      Pattern.compile("a+", Pattern.CASE_INSENSITIVE),
      Pattern.matches("a+", "aa"),
      "aa".matches("a+"),
      "aa".replaceAll("a", "b"),
      "aa".replaceFirst("a", "b"),
      "a,b".split(","),
      "a,b".split(",", 1)
    };
  }
}
