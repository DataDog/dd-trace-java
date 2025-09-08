package datadog.trace.instrumentation.springcloudzuul2;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class HeaderUtils {
  // for now get all the B3, Haystack, Datadog headers and ignore them
  // (headers are all in lowercase for non case sensitiveness)
  public static final Set<String> EXCLUDED_HEADERS =
      new HashSet<String>(
          Arrays.asList(
              // DD headers
              "x-datadog-trace-id",
              "x-datadog-parent-id",
              "x-datadog-sampling-priority",
              "x-datadog-origin",
              // B3 headers
              "x-b3-traceid",
              "x-b3-spanid",
              "x-b3-sampled",
              // Haystack headers
              "trace-id",
              "span-id",
              "parent-id",
              "haystack-trace-id",
              "haystack-span-id",
              "haystack-parent-id"));

  public static final String HAYSTACK_PACKAGE_PREFIX = "baggage-";
  public static final String DD_PACKAGE_PREFIX = "ot-baggage-";
}
