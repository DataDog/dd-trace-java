package datadog.trace.instrumentation.span_origin;

import java.lang.reflect.Method;
import java.util.function.Function;

public class CreateSpanOrigin implements Function<String, EntrySpanOriginInfo> {
  private final Method method;

  public CreateSpanOrigin(Method method) {
    this.method = method;
  }

  @Override
  public EntrySpanOriginInfo apply(String ignored) {
    return new EntrySpanOriginInfo(method);
  }
}
