package datadog.trace.util.stacktrace;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

public abstract class AbstractStackWalker implements StackWalker {

  private static final Predicate<StackTraceElement> NOT_DD_TRACE_STACK_ELEMENT =
      (stackTraceElement) ->
          !stackTraceElement.getClassName().startsWith("datadog.trace.")
              && !stackTraceElement.getClassName().startsWith("com.datadog.iast.");

  @Override
  public <T> T walk(Function<Stream<StackTraceElement>, T> consumer) {
    return doGetStack(input -> consumer.apply(doFilterStack(input)));
  }

  final Stream<StackTraceElement> doFilterStack(Stream<StackTraceElement> stream) {
    return stream.filter(NOT_DD_TRACE_STACK_ELEMENT);
  }

  abstract <T> T doGetStack(Function<Stream<StackTraceElement>, T> consumer);
}
