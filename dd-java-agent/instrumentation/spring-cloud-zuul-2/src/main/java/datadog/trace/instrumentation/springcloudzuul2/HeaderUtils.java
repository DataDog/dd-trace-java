package datadog.trace.instrumentation.springcloudzuul2;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class HeaderUtils {
  // for now get all the B3, Haystack, Datadog headers and ignore them
  public static final Set<String> EXCLUDED_HEADERS =
      new HashSet<String>(
          Arrays.asList(
              // DD headers
              "x-datadog-trace-id",
              "x-datadog-parent-id",
              "x-datadog-sampling-priority",
              "x-datadog-origin",
              // B3 headers
              "X-B3-TraceId",
              "X-B3-SpanId",
              "X-B3-Sampled",
              // Haystack headers
              "Trace-ID",
              "Span-ID",
              "Parent-ID",
              "Haystack-Trace-ID",
              "Haystack-Span-ID",
              "Haystack-Parent-ID"));

  public static final String HAYSTACK_PACKAGE_PREFIX = "Baggage-";
  public static final String DD_PACKAGE_PREFIX = "ot-baggage-";
}
