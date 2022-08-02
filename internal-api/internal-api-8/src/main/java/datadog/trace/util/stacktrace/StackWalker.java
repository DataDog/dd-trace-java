package datadog.trace.util.stacktrace;

import java.util.function.Function;
import java.util.stream.Stream;

public interface StackWalker {

  boolean isEnabled();

  /** StackTrace should be returned without any element from the dd-trace-java-agent itself. */
  <T> T walk(Function<Stream<StackTraceElement>, T> consumer);
}
