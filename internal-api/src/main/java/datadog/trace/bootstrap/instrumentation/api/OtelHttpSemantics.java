package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDTags;
import datadog.trace.api.KnownTags;
import datadog.trace.api.cache.RadixTreeCache;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Shared helpers for emitting OpenTelemetry HTTP semantic conventions. */
public final class OtelHttpSemantics {
  public static final String OTHER_METHOD = "_OTHER";

  // RFC 9110, PATCH (RFC 5789), and QUERY (HTTPbis draft). Method names are case-sensitive.
  private static final Set<String> KNOWN_METHODS =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  "CONNECT", "DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT", "QUERY",
                  "TRACE")));

  private OtelHttpSemantics() {}

  public static void setRequestMethod(final AgentSpan span, final String method) {
    if (method != null && !KNOWN_METHODS.contains(method)) {
      span.setTag(KnownTags.HTTP_METHOD_OTEL_NAME, OTHER_METHOD);
      span.setTag(KnownTags.HTTP_REQUEST_METHOD_ORIGINAL_NAME, method);
    } else {
      span.setTag(KnownTags.HTTP_METHOD_OTEL_NAME, method);
    }
  }

  public static String spanNameMethod(final CharSequence method) {
    return method != null && KNOWN_METHODS.contains(method.toString()) ? method.toString() : "HTTP";
  }

  public static String redactedUrl(final URI url) {
    final String full = url.toString();
    final String userInfo = url.getRawUserInfo();
    if (userInfo == null || userInfo.isEmpty()) {
      return full;
    }
    final String redacted = userInfo.indexOf(':') >= 0 ? "REDACTED:REDACTED" : "REDACTED";
    final int userInfoStart = full.indexOf("//") + 2;
    return full.substring(0, userInfoStart)
        + redacted
        + full.substring(userInfoStart + userInfo.length());
  }

  public static String withoutQuery(final String url) {
    final int fragment = url.indexOf('#');
    final int query = url.indexOf('?');
    if (query < 0 || (fragment >= 0 && fragment < query)) {
      return url;
    }
    if (fragment < 0) {
      return url.substring(0, query);
    }
    return new StringBuilder(url.length() - (fragment - query))
        .append(url, 0, query)
        .append(url, fragment, url.length())
        .toString();
  }

  public static void setErrorType(final AgentSpan span, final int status) {
    if (span.getTag(DDTags.ERROR_TYPE) == null) {
      span.setTag(DDTags.ERROR_TYPE, RadixTreeCache.HTTP_STATUSES.get(status).toString());
    }
  }

  public static int serverPort(final URI url) {
    return serverPort(url.getScheme(), url.getPort());
  }

  public static int serverPort(final String scheme, final int port) {
    if (port > 0) {
      return port;
    }
    if ("https".equalsIgnoreCase(scheme)) {
      return 443;
    }
    if ("http".equalsIgnoreCase(scheme)) {
      return 80;
    }
    return -1;
  }

  public static String forwardedValue(final String value) {
    if (value == null) {
      return null;
    }
    final int comma = value.indexOf(',');
    final String first = (comma >= 0 ? value.substring(0, comma) : value).trim();
    return first.isEmpty() ? null : first;
  }

  public static void setServerAddressAndPort(
      final AgentSpan span,
      final String forwardedHostHeader,
      final String forwardedPortHeader,
      final String scheme,
      final String requestHost,
      final int requestPort) {
    final String forwardedHost = forwardedValue(forwardedHostHeader);
    String serverAddress = requestHost;
    int hostPort = -1;

    if (forwardedHost != null) {
      serverAddress = forwardedHost;
      if (forwardedHost.charAt(0) == '[') {
        final int closingBracket = forwardedHost.indexOf(']');
        if (closingBracket > 0) {
          serverAddress = forwardedHost.substring(1, closingBracket);
          if (closingBracket + 1 < forwardedHost.length()
              && forwardedHost.charAt(closingBracket + 1) == ':') {
            hostPort = parsePort(forwardedHost.substring(closingBracket + 2));
          }
        }
      } else {
        final int colon = forwardedHost.lastIndexOf(':');
        if (colon > 0 && colon == forwardedHost.indexOf(':')) {
          serverAddress = forwardedHost.substring(0, colon);
          hostPort = parsePort(forwardedHost.substring(colon + 1));
        }
      }
    }

    if (serverAddress != null && !serverAddress.isEmpty()) {
      span.setTag(KnownTags.SERVER_ADDRESS_NAME, serverAddress);
    }

    int serverPort = parsePort(forwardedValue(forwardedPortHeader));
    if (serverPort <= 0) {
      serverPort = hostPort;
    }
    if (serverPort <= 0) {
      serverPort = forwardedHost == null && requestPort > 0 ? requestPort : serverPort(scheme, -1);
    }
    if (serverPort > 0) {
      span.setTag(KnownTags.SERVER_PORT_NAME, serverPort);
    }
  }

  public static int parsePort(final String value) {
    if (value == null || value.isEmpty()) {
      return -1;
    }
    try {
      final int port = Integer.parseInt(value);
      return port > 0 && port <= 65535 ? port : -1;
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }
}
