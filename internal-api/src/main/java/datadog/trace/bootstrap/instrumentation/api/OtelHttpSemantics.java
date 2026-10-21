package datadog.trace.bootstrap.instrumentation.api;

import static datadog.trace.util.IntStringUtils.REJECT_LEADING_PLUS;
import static datadog.trace.util.IntStringUtils.parseNonNegativeInt;

import datadog.trace.api.DDTags;
import datadog.trace.api.KnownTags;
import datadog.trace.api.cache.RadixTreeCache;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
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
      span.setTag(KnownTags.HTTP_METHOD_ID, OTHER_METHOD);
      span.setTag(KnownTags.HTTP_REQUEST_METHOD_ORIGINAL_ID, method);
    } else {
      span.setTag(KnownTags.HTTP_METHOD_ID, method);
    }
  }

  public static void setMethodResourceName(final AgentSpan span, final CharSequence method) {
    span.setResourceName(spanNameMethod(method), ResourceNamePriorities.HTTP_PATH_NORMALIZER);
  }

  public static String spanNameMethod(final CharSequence method) {
    final String methodName = method == null ? null : method.toString();
    return KNOWN_METHODS.contains(methodName) ? methodName : "HTTP";
  }

  /** Returns {@code http.url}, falling back to {@code url.path}, without rendering the value. */
  public static Object urlOrPath(final Map<String, ?> tags) {
    Object url = tags.get(Tags.HTTP_URL);
    return url != null ? url : tags.get(KnownTags.URL_PATH_NAME);
  }

  public static String redactedUrl(final URI url) {
    final String full = url.toString();
    // URI does not parse user-info for some hosts, including underscores and Unicode names.
    final String authority = url.getRawAuthority();
    if (authority == null) {
      return full;
    }
    final int userInfoEnd = authority.lastIndexOf('@');
    if (userInfoEnd <= 0) {
      return full;
    }
    final int colon = authority.indexOf(':');
    final String redacted = colon >= 0 && colon < userInfoEnd ? "REDACTED:REDACTED" : "REDACTED";
    final int userInfoStart = full.indexOf("//") + 2;
    return full.substring(0, userInfoStart)
        + redacted
        + full.substring(userInfoStart + userInfoEnd);
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
    return url.substring(0, query) + url.substring(fragment);
  }

  public static void setErrorType(final AgentSpan span, final int status) {
    if (span.getTag(DDTags.ERROR_TYPE) == null) {
      span.setTag(KnownTags.ERROR_TYPE_ID, RadixTreeCache.HTTP_STATUSES.get(status).toString());
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

  /** Returns an explicit authority port, or -1 when absent or invalid. */
  public static int portFromHost(final String host) {
    if (host == null) {
      return -1;
    }
    final int colon = host.lastIndexOf(':');
    if (colon <= 0) {
      return -1;
    }
    if (host.charAt(0) == '[') {
      if (host.indexOf(']') != colon - 1) {
        return -1;
      }
    } else if (host.indexOf(':') != colon) {
      return -1;
    }
    return parsePort(host, colon + 1, host.length() - colon - 1);
  }

  public static int parsePort(final String value) {
    return value == null ? -1 : parsePort(value, 0, value.length());
  }

  private static int parsePort(final String value, final int start, final int length) {
    if (length == 0 || length > 5) {
      return -1;
    }
    final int port = parseNonNegativeInt(value, start, length, REJECT_LEADING_PLUS);
    return port > 0 && port <= 65535 ? port : -1;
  }
}
