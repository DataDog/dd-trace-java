package datadog.trace.civisibility.codeowners.matcher;

public class AsteriskMatcher implements Matcher {

  public static final Matcher INSTANCE = new AsteriskMatcher();

  private AsteriskMatcher() {}

  @Override
  public int consume(char[] line, int offset) {
    return offset < line.length && line[offset] != '/' ? 1 : -1;
  }

  @Override
  public boolean multi() {
    return true;
  }
}
