package datadog.opentelemetry.shim.trace;

/** Test helper providing package-level access to {@link OtelSpanEvent} internals. */
public class OtelSpanEventTestHelper {
  public static String stringifyErrorStack(Throwable error) {
    return OtelSpanEvent.stringifyErrorStack(error);
  }
}
