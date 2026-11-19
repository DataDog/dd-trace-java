package datadog.opentelemetry.shim.trace;

/** Test helper providing package-level access to {@link OtelConventions} internals. */
public class OtelConventionsTestHelper {
  public static String stringifyErrorStack(Throwable error) {
    return OtelConventions.stringifyErrorStack(error);
  }
}
