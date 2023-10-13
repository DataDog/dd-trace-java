package datadog.trace.civisibility.codeowners.matcher;

public class RangeMatcher implements Matcher {

  public static final class Range {
    private final char from;
    private final char to;

    public Range(char from, char to) {
      if (to < from) {
        throw new IllegalArgumentException("Range is invalid: [" + from + "-" + to + "]");
      }
      this.from = from;
      this.to = to;
    }

    boolean matches(char c) {
      return c >= from && c <= to;
    }
  }

  private final Range[] ranges;

  public RangeMatcher(Range... ranges) {
    this.ranges = ranges;
  }

  @Override
  public int consume(char[] line, int offset) {
    if (offset < line.length) {
      for (Range range : ranges) {
        if (range.matches(line[offset])) {
          return 1;
        }
      }
    }
    return -1;
  }

  @Override
  public boolean multi() {
    return false;
  }
}
