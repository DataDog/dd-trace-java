package datadog.trace.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;

public class PropagationUtils {
  private PropagationUtils() {
    // avoid constructing instances of this class.
  }

  /** The list of the known propagation headers. They must be lowercased. */
  public static final Collection<String> KNOWN_PROPAGATION_HEADERS =
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
                  "x-b3-traceid",
                  "x-b3-spanid",
                  "x-b3-sampled",
                  // B3 multi header
                  "b3")));
}
