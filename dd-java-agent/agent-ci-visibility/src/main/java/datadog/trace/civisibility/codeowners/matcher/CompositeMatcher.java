package datadog.trace.civisibility.codeowners.matcher;

public class CompositeMatcher implements Matcher {

  private final Matcher[] delegates;

  public CompositeMatcher(Matcher[] delegates) {
    this.delegates = delegates;
  }

  @Override
  public int consume(char[] line, int offset) {
    return consume(line, offset, 0);
  }

  private int consume(char[] line, int offset, int matcherOffset) {
    int position = offset;
    while (matcherOffset < delegates.length) {
      Matcher delegate = delegates[matcherOffset];
      if (delegate.multi()) {
        int consumed = consume(line, position, matcherOffset + 1);
        if (consumed >= 0) {
          return position - offset + consumed;
        }
      }

      int consumed = delegate.consume(line, position);
      if (consumed < 0) {
        return consumed;
      } else {
        position += consumed;
      }

      if (!delegate.multi()) {
        matcherOffset++;
      }
    }
    return position - offset;
  }

  @Override
  public boolean multi() {
    return false;
  }
}
