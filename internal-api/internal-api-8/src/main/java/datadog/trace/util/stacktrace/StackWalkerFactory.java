package datadog.trace.util.stacktrace;

import java.util.stream.Stream;

public class StackWalkerFactory {
  static StackWalker getInstance() {
    // New StackTraceGenerator must be added to the list
    return Stream.of(new DefaultStackWalker())
        .filter(StackWalker::isEnabled)
        .findFirst()
        .orElse(new DefaultStackWalker());
  }
}
