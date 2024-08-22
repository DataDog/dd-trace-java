package datadog.trace.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;

public class PropagationUtils {
  private static final Collection<String> KNOWN_PROPAGATION_HEADERS =
      Collections.unmodifiableCollection(
          new LinkedHashSet<>(
              Arrays.asList(
                  // W3C headers
                  "traceparent",
                  "tracestate",
                  // DD headers
                  "x-datadog-trace-id",
                  "x-datadog-parent-id",
                  "x-datadog-sampling-priority",
                  "x-datadog-origin",
                  "x-datadog-tags",
                  // B3 single headers
                  "X-B3-TraceId",
                  "X-B3-SpanId",
                  "X-B3-Sampled",
                  // B3 multi header
                  "b3")));

  public static Collection<String> getAllHeaders() {
    return ALL_HEADERS;
  }
}
