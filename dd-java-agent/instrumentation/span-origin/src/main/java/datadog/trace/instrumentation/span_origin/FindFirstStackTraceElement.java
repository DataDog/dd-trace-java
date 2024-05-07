package datadog.trace.instrumentation.span_origin;

import java.lang.reflect.Method;
import java.util.function.Function;
import java.util.stream.Stream;

public class FindFirstStackTraceElement
    implements Function<Stream<StackTraceElement>, StackTraceElement> {
  private final Method method;

  public FindFirstStackTraceElement(Method method) {
    this.method = method;
  }

  @Override
  public StackTraceElement apply(Stream<StackTraceElement> stream) {
    return stream
        .filter(
            element ->
                element.getClassName().equals(method.getDeclaringClass().getName())
                    && element.getMethodName().equals(method.getName()))
        .findFirst()
        .orElseThrow(() -> new RuntimeException("No stack trace available"));
  }
}
