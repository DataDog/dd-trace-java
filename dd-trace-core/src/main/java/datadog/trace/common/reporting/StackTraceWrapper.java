package datadog.trace.common.reporting;

import java.util.Arrays;
import java.util.Objects;

/**
 * Wrapper for StackTraceWrapper objects for easier stack trace to stack trace comparisons.
 * Number of stack trace lines collected was arbitrarily set to 3.
 */
public class StackTraceWrapper {
  private static final int STACK_TRACE_ELEMENTS_MAX_INDEX = 3;
  String msg;
  StackTraceElement[] stackTraceElements;

  /**
   * Constructor for StackTraceWrapper
   * Takes up to first 3 lines of stack trace to store in stackTraceElements instance variable
   * @param msg
   * @param stackTraceElements
   */
  public StackTraceWrapper(String msg, StackTraceElement[] stackTraceElements) {
    this.msg = msg;
    this.stackTraceElements = Arrays.copyOfRange(stackTraceElements, 0,
        Math.min(STACK_TRACE_ELEMENTS_MAX_INDEX, stackTraceElements.length));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    StackTraceWrapper that = (StackTraceWrapper) o;

    if (!Objects.equals(msg, that.msg)) return false;
    return Arrays.deepEquals(stackTraceElements, that.stackTraceElements);
  }

  @Override
  public int hashCode() {
    int result = msg != null ? msg.hashCode() : 0;
    result = 31 * result + Arrays.hashCode(stackTraceElements);
    return result;
  }
}
