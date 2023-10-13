package datadog.trace.civisibility.codeowners.matcher;

public interface Matcher {

  /**
   * @return the number of characters matched from the line starting with the offset. Negative value
   *     means matching failed
   */
  int consume(char[] line, int offset);

  /**
   * @return {@code true} if this matcher can be used [0..*] times. {@code false} if the matcher
   *     should be used exactly once
   */
  boolean multi();
}
