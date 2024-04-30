package datadog.trace.agent.tooling.iast.stratum.utils;

import java.util.regex.Pattern;

public class PatternUtils {

  public static final int DEFAULT_ITERATIONS = 5000000;

  private static final RuntimeException BLOCKING = new RuntimeException();

  private static final Runnable BLOCKER =
      () -> {
        throw BLOCKING;
      };

  private PatternUtils() {}

  public static LimitedPattern compile(final String pattern) {
    return new LimitedPattern(Pattern.compile(pattern));
  }

  public static LimitedPattern compile(final String pattern, final int flags) {
    return new LimitedPattern(Pattern.compile(pattern, flags));
  }

  public static LimitedPattern compile(final Pattern pattern) {
    if (pattern != null) {
      return new LimitedPattern(pattern);
    }
    return null;
  }

  public static class LimitedPattern {

    Pattern pattern;

    LimitedPattern(final Pattern pattern) {
      this.pattern = pattern;
    }

    public LimitedMatcher matcher(final CharSequence seq) {
      return new LimitedMatcher(
          seq, pattern.matcher(new StoppableCharSequence(seq, DEFAULT_ITERATIONS, BLOCKER)));
    }

    public String pattern() {
      return pattern.pattern();
    }

    public Pattern internal() {
      return pattern;
    }

    @Override
    public String toString() {
      return pattern();
    }
  }

  public static class LimitedMatcher {

    private final java.util.regex.Matcher jmatcher;

    private final CharSequence seq;

    public LimitedMatcher(final CharSequence seq, final java.util.regex.Matcher jmatcher) {
      this.seq = seq;
      this.jmatcher = jmatcher;
    }

    public boolean find() {
      try {
        return jmatcher.find();
      } catch (RuntimeException e) {
        return false;
      }
    }

    public boolean matches() {
      try {
        return jmatcher.matches();
      } catch (RuntimeException e) {
        return false;
      }
    }

    public String replaceFirst(final String replacement) {
      try {
        return jmatcher.replaceFirst(replacement);
      } catch (RuntimeException e) {
        return String.valueOf(seq);
      }
    }

    public String replaceAll(final String replacement) {
      try {
        return jmatcher.replaceAll(replacement);
      } catch (RuntimeException e) {
        return String.valueOf(seq);
      }
    }

    public String group(final int group) {
      return jmatcher.group(group);
    }

    public int start() {
      return jmatcher.start();
    }

    public int end() {
      return jmatcher.end();
    }

    public LimitedMatcher appendReplacement(final StringBuffer sb, final String replacement) {
      jmatcher.appendReplacement(sb, replacement);
      return this;
    }

    public StringBuffer appendTail(final StringBuffer sb) {
      return jmatcher.appendTail(sb);
    }
  }
}
