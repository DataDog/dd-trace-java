package datadog.trace.bootstrap.instrumentation.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Shared OpenTelemetry HTTP request-method helpers, used by the HTTP decorators (for the span name)
 * and by the core OTel-semantics tag post-processor (for {@code http.request.method}
 * normalization). See <a href="https://opentelemetry.io/docs/specs/semconv/http/http-spans/">the
 * spec</a>.
 */
public final class OtelHttpMethods {
  private OtelHttpMethods() {}

  /** The value {@code http.request.method} takes when the request method isn't recognized. */
  public static final String OTHER_METHOD = "_OTHER";

  // "Known" HTTP methods per the spec: RFC 9110 + PATCH (RFC 5789) + QUERY (httpbis draft).
  // Method names are case-sensitive and must match exactly.
  private static final Set<String> KNOWN_METHODS =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  "CONNECT", "DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT", "QUERY",
                  "TRACE")));

  public static boolean isKnown(final String method) {
    return method != null && KNOWN_METHODS.contains(method);
  }

  /**
   * Returns the method component to use in the span name. Per the spec, when the request method is
   * unknown the span name uses the literal {@code HTTP} rather than the raw verb (and never the URL
   * path).
   */
  public static String spanName(final String method) {
    return isKnown(method) ? method : "HTTP";
  }
}
