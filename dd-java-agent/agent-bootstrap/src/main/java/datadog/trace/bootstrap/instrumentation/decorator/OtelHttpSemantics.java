package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Helpers for emitting OpenTelemetry HTTP semantic-convention attributes, shared by the HTTP server
 * and client decorators. See https://opentelemetry.io/docs/specs/semconv/http/http-spans/
 */
final class OtelHttpSemantics {
  private OtelHttpSemantics() {}

  static final String OTHER_METHOD = "_OTHER";

  // "Known" HTTP methods per the spec: RFC 9110 + PATCH (RFC 5789) + QUERY (httpbis draft).
  // Method names are case-sensitive and must match exactly.
  private static final Set<String> KNOWN_METHODS =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  "CONNECT", "DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT", "QUERY",
                  "TRACE")));

  /**
   * Sets {@code http.request.method}, normalizing methods the instrumentation doesn't know to
   * {@code _OTHER} and recording the original value in {@code http.request.method_original}.
   */
  static void setRequestMethod(final AgentSpan span, final String method) {
    if (method != null && !KNOWN_METHODS.contains(method)) {
      span.setTag(Tags.HTTP_REQUEST_METHOD, OTHER_METHOD);
      span.setTag(Tags.HTTP_REQUEST_METHOD_ORIGINAL, method);
    } else {
      span.setTag(Tags.HTTP_REQUEST_METHOD, method);
    }
  }

  /**
   * Returns the value for {@code url.full} with any embedded credentials redacted: the spec
   * mandates that {@code url.full} MUST NOT contain credentials (e.g. {@code
   * https://user:pass@host} becomes {@code https://REDACTED:REDACTED@host}).
   */
  static String redactedUrl(final URI url) {
    final String full = url.toString();
    final String userInfo = url.getRawUserInfo();
    if (userInfo == null || userInfo.isEmpty()) {
      return full;
    }
    // Redact only the components that are actually present (user vs user:password) so we don't
    // imply a password that wasn't there.
    final String redacted = userInfo.indexOf(':') >= 0 ? "REDACTED:REDACTED" : "REDACTED";
    return full.replace(userInfo + "@", redacted + "@");
  }

  /**
   * Strips the query string and fragment from a URL, used to honor the client query-string opt-out
   * ({@code DD_TRACE_HTTP_CLIENT_TAG_QUERY_STRING=false}) for {@code url.full}.
   */
  static String withoutQueryAndFragment(final String url) {
    int cut = url.length();
    final int query = url.indexOf('?');
    if (query >= 0) {
      cut = query;
    }
    final int fragment = url.indexOf('#');
    if (fragment >= 0 && fragment < cut) {
      cut = fragment;
    }
    return url.substring(0, cut);
  }

  /**
   * Sets {@code error.type} to the HTTP status code (as a string) when the response indicates an
   * error, unless an error type (e.g. from a thrown exception) has already been recorded — the spec
   * prefers the exception type over the status code.
   */
  static void setErrorType(final AgentSpan span, final int status) {
    if (span.getTag(DDTags.ERROR_TYPE) == null) {
      span.setTag(DDTags.ERROR_TYPE, Integer.toString(status));
    }
  }

  /**
   * Resolves {@code server.port} for a client span, falling back to the scheme default (80/443)
   * when the URL omits an explicit port, since the spec marks it required for client spans.
   */
  static int serverPort(final URI url) {
    final int port = url.getPort();
    if (port > 0) {
      return port;
    }
    if ("https".equals(url.getScheme())) {
      return 443;
    }
    if ("http".equals(url.getScheme())) {
      return 80;
    }
    return -1;
  }
}
