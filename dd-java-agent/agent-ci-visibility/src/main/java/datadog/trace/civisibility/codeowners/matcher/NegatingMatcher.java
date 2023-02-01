package datadog.trace.civisibility.codeowners.matcher;

public class NegatingMatcher implements Matcher {

  private final Matcher delegate;

  public NegatingMatcher(Matcher delegate) {
    this.delegate = delegate;
  }

  @Override
  public int consume(char[] line, int offset) {
    return -delegate.consume(line, offset) - 1;
  }

  @Override
  public boolean multi() {
    return false;
  }
}
