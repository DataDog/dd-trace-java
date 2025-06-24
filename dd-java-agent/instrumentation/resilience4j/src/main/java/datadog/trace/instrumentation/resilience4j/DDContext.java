package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

// TODO rename it to a utility method
public final class DDContext {

  public static final CharSequence SPAN_NAME = UTF8BytesString.create("resilience4j");

  public static final String INSTRUMENTATION_NAME = "resilience4j";
}
