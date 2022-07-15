package datadog.trace.util.stacktrace;

import java.util.stream.Stream;

public class StackWalkerFactory {

  public static StackWalker INSTANCE = getInstance();

  private static StackWalker getInstance() {

    // New StackTraceGenerator must be added to the list
    Stream<StackWalker> implementations = Stream.of(new HotSpotStackWalker());
    return implementations
        .filter(StackWalker::isEnabled)
        .findFirst()
        .orElse(new DefaultStackWalker());
  }
}
