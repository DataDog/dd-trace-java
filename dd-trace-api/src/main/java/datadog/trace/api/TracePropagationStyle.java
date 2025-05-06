package datadog.trace.api;

import java.util.Locale;

/** Trace propagation styles for injecting and extracting trace propagation headers. */
public enum TracePropagationStyle {
  // Datadog context propagation style
  DATADOG,
  // B3 single header context propagation style
  // https://github.com/openzipkin/b3-propagation/tree/master#single-header
  B3SINGLE,
  // B3 multi header context propagation style
  // https://github.com/openzipkin/b3-propagation/tree/master#multiple-headers
  B3MULTI,
  // Haystack context propagation style
  // https://github.com/ExpediaDotCom/haystack
  HAYSTACK,
  // Amazon X-Ray context propagation style
  // https://docs.aws.amazon.com/xray/latest/devguide/xray-concepts.html#xray-concepts-tracingheader
  XRAY,
  // W3C trace context propagation style
  // https://www.w3.org/TR/trace-context-1/
  TRACECONTEXT,
  // W3C baggage support
  // https://www.w3.org/TR/baggage/
  BAGGAGE,
  // None does not extract or inject
  NONE;

  public static TracePropagationStyle valueOfDisplayName(String displayName) {
    String convertedName = displayName.toUpperCase().replace(' ', '_');
    // Another name for B3 for cross tracer compatibility
    switch (convertedName) {
      case "B3_SINGLE_HEADER":
        return B3SINGLE;
      case "B3":
        return B3MULTI;
      default:
        return TracePropagationStyle.valueOf(convertedName);
    }
  }

  private String displayName;

  @Override
  public String toString() {
    String string = displayName;
    if (displayName == null) {
      string = displayName = name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
    return string;
  }
}
