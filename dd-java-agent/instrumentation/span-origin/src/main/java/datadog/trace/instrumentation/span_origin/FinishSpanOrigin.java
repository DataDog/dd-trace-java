package datadog.trace.instrumentation.span_origin;

import java.util.function.Function;

public class FinishSpanOrigin implements Function<String, EntrySpanOriginInfo> {
  @Override
  public EntrySpanOriginInfo apply(String method) {
    throw new IllegalStateException("No entry span info found for method " + method);
  }
}
