package datadog.trace.civisibility.codeowners.matcher;

public class DoubleAsteriskMatcher implements Matcher {

  public static final Matcher INSTANCE = new DoubleAsteriskMatcher();

  private DoubleAsteriskMatcher() {}

  @Override
  public int consume(char[] line, int offset) {
    if (offset == line.length) {
      return -1;
    }

    int position = offset;
    while (position < line.length && line[position++] != '/') {}
    return position - offset;
  }

  @Override
  public boolean multi() {
    return true;
  }
}
