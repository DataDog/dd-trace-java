package datadog.trace.util.stacktrace;

import java.util.Arrays;
import java.util.function.Predicate;
import java.util.stream.Stream;

public abstract class AbstractStackWalker implements StackWalker {

  private static final Predicate<StackTraceElement> NOT_DD_TRACE_CLASS =
      (stackTraceElement) ->
          !stackTraceElement.getClassName().startsWith("datadog.trace.")
              && !stackTraceElement.getClassName().startsWith("com.datadog.appsec.");

  Stream<StackTraceElement> doGetStack() {
    return Arrays.stream(new Throwable().getStackTrace());
  }

  Stream<StackTraceElement> doFilterStack(Stream<StackTraceElement> stream) {
    return stream.filter(NOT_DD_TRACE_CLASS);
  }
}
