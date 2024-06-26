package datadog.trace.civisibility.codeowners.matcher;

public class EndOfSegmentMatcher implements Matcher {

  public static final Matcher INSTANCE = new EndOfSegmentMatcher();

  @Override
  public int consume(char[] line, int offset) {
    return offset == line.length || line[offset] == '/' ? 0 : -1;
  }

  @Override
  public boolean multi() {
    return false;
  }
}
