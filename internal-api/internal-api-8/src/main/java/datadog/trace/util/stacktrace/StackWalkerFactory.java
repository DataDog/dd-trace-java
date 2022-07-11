package datadog.trace.util.stacktrace;

public class StackWalkerFactory {

  public static StackWalker INSTANCE = getInstance();

  private static StackWalker getInstance() {
    // New StackWalker implementations must be added in the future
    return new DefaultStackWalker();
  }
}
