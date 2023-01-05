package datadog.trace.api;

/** Trace propagation styles for injecting and extracting trace propagation headers. */
public enum TracePropagationStyle {
  // Datadog context propagation style
  DATADOG,
  // B3 single header context propagation style
  // https://github.com/openzipkin/b3-propagation/tree/master#single-header
  B3,
  // B3 multi header context propagation style
  // https://github.com/openzipkin/b3-propagation/tree/master#multiple-headers
  B3MULTI,
  // Haystack context propagation style
  // https://github.com/ExpediaDotCom/haystack
  HAYSTACK,
  // Amazon X-Ray context propagation style
  // https://docs.aws.amazon.com/xray/latest/devguide/xray-concepts.html#xray-concepts-tracingheader
  XRAY;

  public static TracePropagationStyle valueOfDisplayName(String displayName) {
    String convertedName = displayName.toUpperCase().replace(' ', '_');
    // Another name for B3 for cross tracer compatibility
    if (convertedName.equals("B3_SINGLE_HEADER")) {
      return B3;
    }
    return TracePropagationStyle.valueOf(convertedName);
  }

  private String displayName;

  @Override
  public String toString() {
    String string = displayName;
    if (displayName == null) {
      string = displayName = name().toLowerCase().replace('_', ' ');
    }
    return string;
  }
}
