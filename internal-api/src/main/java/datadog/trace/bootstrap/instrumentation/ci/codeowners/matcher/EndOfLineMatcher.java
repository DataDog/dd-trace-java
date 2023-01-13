package datadog.trace.bootstrap.instrumentation.ci.codeowners.matcher;

public class EndOfLineMatcher implements Matcher {

  public static final Matcher INSTANCE = new EndOfLineMatcher();

  @Override
  public int consume(char[] line, int offset) {
    return offset == line.length ? 0 : -1;
  }

  @Override
  public boolean multi() {
    return false;
  }
}
