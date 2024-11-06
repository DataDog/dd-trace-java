package datadog.trace.util.stacktrace;

import java.util.function.Function;
import java.util.stream.Stream;

public abstract class AbstractStackWalker implements StackWalker {

  @Override
  public <T> T walk(Function<Stream<StackTraceElement>, T> consumer) {
    return doGetStack(input -> consumer.apply(doFilterStack(input)));
  }

  final Stream<StackTraceElement> doFilterStack(Stream<StackTraceElement> stream) {
    return stream.filter(AbstractStackWalker::isNotDatadogTraceStackElement);
  }

  abstract <T> T doGetStack(Function<Stream<StackTraceElement>, T> consumer);

  static boolean isNotDatadogTraceStackElement(final StackTraceElement el) {
    final String clazz = el.getClassName();
    return !clazz.startsWith("datadog.trace.")
        && !clazz.startsWith("com.datadog.iast.")
        && !clazz.startsWith("com.datadog.appsec.");
  }
}
