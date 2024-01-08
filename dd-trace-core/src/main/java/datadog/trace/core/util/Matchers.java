package datadog.trace.core.util;

import java.util.regex.Pattern;

public final class Matchers {
  private Matchers() {}

  public static Matcher compileGlob(String glob) {
    if (glob == null || glob.equals("*")) {
      // DQH - Decided not to an anyMatcher because that's likely to
      // cause our call site to go megamorphic
      return null;
    } else if (isExact(glob)) {
      return new ExactMatcher(glob);
    } else {
      // DQH - not sure about the error handling here
      Pattern pattern = GlobPattern.globToRegexPattern(glob);
      return new PatternMatcher(pattern);
    }
  }

  public static boolean matches(Matcher matcher, String str) {
    return (matcher == null) || matcher.matches(str);
  }

  public static boolean matches(Matcher matcher, CharSequence charSeq) {
    return (matcher == null) || matcher.matches(charSeq);
  }

  static boolean isExact(String glob) {
    return (glob.indexOf('*') == -1) && (glob.indexOf('?') == -1);
  }

  static final class ExactMatcher implements Matcher {
    private final String exact;

    ExactMatcher(String exact) {
      this.exact = exact;
    }

    @Override
    public boolean matches(String str) {
      return exact.equals(str);
    }

    @Override
    public boolean matches(CharSequence charSeq) {
      return exact.contentEquals(charSeq);
    }
  }

  static final class PatternMatcher implements Matcher {
    private final Pattern pattern;

    PatternMatcher(Pattern pattern) {
      this.pattern = pattern;
    }

    @Override
    public boolean matches(CharSequence charSeq) {
      return pattern.matcher(charSeq).matches();
    }

    @Override
    public boolean matches(String str) {
      return pattern.matcher(str).matches();
    }
  }
}
