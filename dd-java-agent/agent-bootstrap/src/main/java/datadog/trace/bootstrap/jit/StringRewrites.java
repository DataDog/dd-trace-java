package datadog.trace.bootstrap.jit;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Drop-in replacements for regex-based {@link String} methods, substituted at their call sites.
 *
 * <p>Each helper takes exactly the call's operands (receiver first), so a call site is rewritten by
 * replacing one invoke instruction. Each returns exactly what the original method returns: a fast
 * path is only taken for regex shapes it reproduces exactly, and everything else (including {@code
 * null} arguments and invalid regexes) is delegated to the original method, which then throws its
 * own exceptions. The only visible difference is one extra stack frame.
 *
 * <p>Caching compiled patterns only pays where compiling dominates: it's used for {@code matches},
 * but not as a general fallback for {@code split}/{@code replaceAll}/{@code replaceFirst}, where it
 * measured slower than the JDK for small patterns.
 */
public final class StringRewrites {
  private StringRewrites() {}

  /** Before JDK 13, {@code String.replace(CharSequence, CharSequence)} used a literal Pattern. */
  private static final boolean JDK_REPLACE_IS_REGEX = specificationVersion() < 13;

  private static int specificationVersion() {
    try {
      String version = System.getProperty("java.specification.version", "");
      return Integer.parseInt(version.startsWith("1.") ? version.substring(2) : version);
    } catch (RuntimeException e) {
      return 8; // assume the older behavior
    }
  }

  /** Replaces every occurrence of a non-empty literal, left to right, like String.replace. */
  static String replaceLiteral(String s, String target, String replacement) {
    return JDK_REPLACE_IS_REGEX
        ? replaceLiteralByScanning(s, target, replacement)
        : s.replace(target, replacement);
  }

  /** The pre-JDK-13 path, without java.util.regex; package-private for tests. */
  static String replaceLiteralByScanning(String s, String target, String replacement) {
    int index = s.indexOf(target);
    if (index < 0) {
      return s;
    }
    int targetLength = target.length();
    StringBuilder result = new StringBuilder(s.length() + 16);
    int from = 0;
    do {
      result.append(s, from, index).append(replacement);
      from = index + targetLength;
      index = s.indexOf(target, from);
    } while (index >= 0);
    return result.append(s, from, s.length()).toString();
  }

  /** {@link String#replaceAll(String, String)}. */
  public static String replaceAll(String s, String regex, String replacement) {
    if (null != s && null != regex && null != replacement) {
      String literal = RegexShape.literal(regex);
      if (null != literal && RegexShape.isLiteralReplacement(replacement)) {
        return replaceLiteral(s, literal, replacement);
      }
    }
    return s.replaceAll(regex, replacement);
  }

  /** {@link String#replaceFirst(String, String)}. */
  public static String replaceFirst(String s, String regex, String replacement) {
    if (null != s && null != regex && null != replacement) {
      String literal = RegexShape.literal(regex);
      if (null != literal && RegexShape.isLiteralReplacement(replacement)) {
        int index = s.indexOf(literal);
        if (index < 0) {
          return s;
        }
        return s.substring(0, index) + replacement + s.substring(index + literal.length());
      }
    }
    return s.replaceFirst(regex, replacement);
  }

  /** {@link String#split(String)}. */
  public static String[] split(String s, String regex) {
    return split(s, regex, 0);
  }

  /** {@link String#split(String, int)}. */
  public static String[] split(String s, String regex, int limit) {
    if (null != s && null != regex && !hasJdkFastPath(regex)) {
      RegexShape shape = RegexShape.of(regex);
      if (shape != RegexShape.OTHER) {
        return split(s, regex, shape, limit);
      }
    }
    return s.split(regex, limit);
  }

  /** {@link String#matches(String)}. */
  public static boolean matches(String s, String regex) {
    if (null != s && null != regex) {
      Pattern pattern = PatternCache.get(regex);
      if (null != pattern) {
        return pattern.matcher(s).matches();
      }
    }
    return s.matches(regex);
  }

  /** {@link Pattern#matches(String, CharSequence)}. */
  public static boolean patternMatches(String regex, CharSequence input) {
    if (null != regex && null != input) {
      Pattern pattern = PatternCache.get(regex);
      if (null != pattern) {
        return pattern.matcher(input).matches();
      }
    }
    return Pattern.matches(regex, input);
  }

  /**
   * Whether {@link String#split} already avoids {@code java.util.regex} for this regex: a single
   * non-metacharacter, or a backslash followed by a single non-alphanumeric character.
   */
  static boolean hasJdkFastPath(String regex) {
    int length = regex.length();
    char ch;
    return (length == 1 && ".$|()[{^?*+\\".indexOf(ch = regex.charAt(0)) == -1)
        || (length == 2
            && regex.charAt(0) == '\\'
            && ((ch = regex.charAt(1)) - '0' | '9' - ch) < 0
            && (ch - 'a' | 'z' - ch) < 0
            && (ch - 'A' | 'Z' - ch) < 0);
  }

  /** Splits with a hand-written matcher, following {@link Pattern#split(CharSequence, int)}. */
  private static String[] split(String input, String regex, RegexShape shape, int limit) {
    Finder finder = new Finder(input, regex, shape);
    int index = 0;
    boolean matchLimited = limit > 0;
    ArrayList<String> matchList = new ArrayList<>();
    while (finder.find(index)) {
      if (!matchLimited || matchList.size() < limit - 1) {
        // no shape here matches the empty string, so there's no zero-width leading match to skip
        matchList.add(input.substring(index, finder.start));
        index = finder.end;
      } else if (matchList.size() == limit - 1) {
        matchList.add(input.substring(index));
        index = finder.end;
        break;
      }
    }
    if (index == 0) {
      return new String[] {input};
    }
    if (!matchLimited || matchList.size() < limit) {
      matchList.add(input.substring(index));
    }
    int resultSize = matchList.size();
    if (limit == 0) {
      while (resultSize > 0 && matchList.get(resultSize - 1).isEmpty()) {
        resultSize--;
      }
    }
    return matchList.subList(0, resultSize).toArray(new String[resultSize]);
  }

  /** Finds successive non-overlapping matches of a recognized shape, leftmost first. */
  private static final class Finder {
    private final String input;
    private final RegexShape shape;
    private final String literal;
    private final char separator;
    int start;
    int end;

    Finder(String input, String regex, RegexShape shape) {
      this.input = input;
      this.shape = shape;
      this.literal = shape == RegexShape.LITERAL ? RegexShape.literal(regex) : null;
      this.separator =
          shape == RegexShape.PADDED_SEPARATOR || shape == RegexShape.TRAILING_PADDED_SEPARATOR
              ? RegexShape.separator(regex)
              : 0;
    }

    /** Finds the next match at or after {@code from}; sets {@link #start} and {@link #end}. */
    boolean find(int from) {
      int length = input.length();
      switch (shape) {
        case LITERAL:
          {
            int i = input.indexOf(literal, from);
            if (i < 0) {
              return false;
            }
            start = i;
            end = i + literal.length();
            return true;
          }
        case WHITESPACE_RUN:
        case SPACE_RUN:
          {
            boolean spacesOnly = shape == RegexShape.SPACE_RUN;
            int i = from;
            while (i < length && !isRunChar(input.charAt(i), spacesOnly)) {
              i++;
            }
            if (i >= length) {
              return false;
            }
            start = i;
            while (i < length && isRunChar(input.charAt(i), spacesOnly)) {
              i++;
            }
            end = i;
            return true;
          }
        case PADDED_SEPARATOR:
        case TRAILING_PADDED_SEPARATOR:
          {
            int i = input.indexOf(separator, from);
            if (i < 0) {
              return false;
            }
            int matchStart = i;
            if (shape == RegexShape.PADDED_SEPARATOR) {
              // leftmost match starts at the whitespace run directly before the separator
              while (matchStart > from && RegexShape.isWhitespace(input.charAt(matchStart - 1))) {
                matchStart--;
              }
            }
            int matchEnd = i + 1;
            while (matchEnd < length && RegexShape.isWhitespace(input.charAt(matchEnd))) {
              matchEnd++;
            }
            start = matchStart;
            end = matchEnd;
            return true;
          }
        default:
          return false;
      }
    }

    private static boolean isRunChar(char c, boolean spacesOnly) {
      return spacesOnly ? c == ' ' : RegexShape.isWhitespace(c);
    }
  }
}
