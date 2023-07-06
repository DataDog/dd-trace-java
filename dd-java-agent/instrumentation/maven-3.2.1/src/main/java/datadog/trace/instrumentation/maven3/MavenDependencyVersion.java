package datadog.trace.instrumentation.maven3;

import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Pattern;

public class MavenDependencyVersion {

  public static final MavenDependencyVersion UNKNOWN = new MavenDependencyVersion(new int[0], null);

  private static final Pattern DOT = Pattern.compile("\\.");
  private final int[] tokens;
  private final String remainder;

  MavenDependencyVersion(int[] tokens, String remainder) {
    this.tokens = tokens;
    this.remainder = remainder;
  }

  public boolean isLaterThanOrEqualTo(MavenDependencyVersion other) {
    int length = Math.max(tokens.length, other.tokens.length);
    for (int i = 0; i < length; i++) {
      int thisToken = i < tokens.length ? tokens[i] : 0;
      int otherToken = i < other.tokens.length ? other.tokens[i] : 0;
      if (thisToken > otherToken) {
        return true;
      }
      if (otherToken > thisToken) {
        return false;
      }
    }
    if (tokens.length != other.tokens.length) {
      return tokens.length >= other.tokens.length;
    }
    if (remainder == null && other.remainder != null) {
      return true;
    }
    if (remainder != null && other.remainder == null) {
      return false;
    }
    return Objects.compare(remainder, other.remainder, Comparator.naturalOrder()) > 0;
  }

  public static MavenDependencyVersion from(String s) {
    try {
      String remainder = null;
      int dashIndex = s.indexOf('-');
      if (dashIndex >= 0) {
        remainder = s.substring(dashIndex);
        s = s.substring(0, dashIndex);
      }

      String[] stringTokens = DOT.split(s);
      int[] tokens = new int[stringTokens.length];
      for (int i = 0; i < stringTokens.length; i++) {
        tokens[i] = Integer.parseInt(stringTokens[i]);
      }
      return new MavenDependencyVersion(tokens, remainder);
    } catch (Exception e) {
      return UNKNOWN;
    }
  }
}
