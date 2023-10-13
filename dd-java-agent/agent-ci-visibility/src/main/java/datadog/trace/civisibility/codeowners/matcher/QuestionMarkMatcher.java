package datadog.trace.civisibility.codeowners.matcher;

public class QuestionMarkMatcher implements Matcher {

  public static final Matcher INSTANCE = new QuestionMarkMatcher();

  private QuestionMarkMatcher() {}

  @Override
  public int consume(char[] line, int offset) {
    return offset < line.length && line[offset] != '/' ? 1 : -1;
  }

  @Override
  public boolean multi() {
    return false;
  }
}
