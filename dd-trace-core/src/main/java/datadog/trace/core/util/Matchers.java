package datadog.trace.core.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

public final class Matchers {
  public static final Matcher ANY = new AnyMatcher();

  private Matchers() {}

  public static Matcher compileGlob(String glob) {
    if (glob == null || isAny(glob)) {
      return ANY;
    } else if (isExact(glob)) {
      return new InsensitiveEqualsMatcher(glob);
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

  static final boolean isAny(String glob) {
    if ("*".equals(glob)) {
      return true;
    } else if (glob.length() == 0) {
      return false;
    } else {
      for (int i = 0; i < glob.length(); ++i) {
        if (glob.charAt(i) != '*') return false;
      }
      return true;
    }
  }

  static boolean isExact(String glob) {
    return (glob.indexOf('*') == -1) && (glob.indexOf('?') == -1);
  }

  static final class AnyMatcher implements Matcher {
    @Override
    public boolean matches(CharSequence charSeq) {
      return true;
    }

    @Override
    public boolean matches(String str) {
      return true;
    }

    @Override
    public boolean matches(Object value) {
      return true;
    }

    @Override
    public boolean matches(boolean value) {
      return true;
    }

    @Override
    public boolean matches(byte value) {
      return true;
    }

    @Override
    public boolean matches(short value) {
      return true;
    }

    @Override
    public boolean matches(int value) {
      return true;
    }

    @Override
    public boolean matches(long value) {
      return true;
    }

    @Override
    public boolean matches(BigInteger value) {
      return true;
    }

    @Override
    public boolean matches(double value) {
      return true;
    }

    @Override
    public boolean matches(float value) {
      return true;
    }

    @Override
    public boolean matches(BigDecimal value) {
      return true;
    }
  }

  static final class InsensitiveEqualsMatcher extends BaseMatcher {
    private final String exact;

    InsensitiveEqualsMatcher(String exact) {
      this.exact = exact;
    }

    @Override
    public boolean matches(String str) {
      return exact.equalsIgnoreCase(str);
    }

    @Override
    public boolean matches(CharSequence charSeq) {
      int exactLen = exact.length();
      int charSeqLen = charSeq.length();
      if (exactLen != charSeqLen) {
        return false;
      } else if (charSeq instanceof String) {
        return matches((String) charSeq);
      } else {
        // same length, but need to do insensitive compare
        for (int i = 0; i < exactLen && i < charSeqLen; i++) {
          char exactLowerChar = Character.toLowerCase(exact.charAt(i));
          char charSeqLowerChar = Character.toLowerCase(charSeq.charAt(i));

          if (exactLowerChar != charSeqLowerChar) {
            return false;
          }
        }
        return true;
      }
    }
  }

  static final class PatternMatcher extends BaseMatcher {
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
