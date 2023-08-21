package datadog.trace.instrumentation.maven3;

import java.util.regex.Pattern;

public class MavenDependencyVersion {

  public static final MavenDependencyVersion UNKNOWN = new MavenDependencyVersion();

  private static final Pattern DOT = Pattern.compile("\\.");
  private final int[] tokens;

  MavenDependencyVersion(int... tokens) {
    this.tokens = tokens;
  }

  boolean isLaterThanOrEqualTo(MavenDependencyVersion other) {
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
    return tokens.length >= other.tokens.length;
  }

  static MavenDependencyVersion from(String s) {
    String[] stringTokens = DOT.split(s);
    int[] tokens = new int[stringTokens.length];
    for (int i = 0; i < stringTokens.length; i++) {
      tokens[i] = Integer.parseInt(stringTokens[i]);
    }
    return new MavenDependencyVersion(tokens);
  }
}
