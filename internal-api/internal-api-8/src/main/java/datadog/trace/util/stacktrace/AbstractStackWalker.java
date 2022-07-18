package datadog.trace.util.stacktrace;

import java.util.Arrays;
import java.util.function.Predicate;
import java.util.stream.Stream;

public abstract class AbstractStackWalker implements StackWalker {

  protected static final Predicate<String> NOT_DD_TRACE_CLASS =
      (className) ->
          !className.startsWith("datadog.trace.") && !className.startsWith("com.datadog.appsec.");

  protected static final Predicate<StackTraceElement> NOT_DD_TRACE_STACKELEMENT =
      (stackElement) -> NOT_DD_TRACE_CLASS.test(stackElement.getClassName());

  Stream<StackTraceElement> doGetStack() {
    return Arrays.stream(new Throwable().getStackTrace());
  }

  Stream<StackTraceElement> doFilterStack(Stream<StackTraceElement> stream) {
    return stream.filter(NOT_DD_TRACE_STACKELEMENT);
  }
}
