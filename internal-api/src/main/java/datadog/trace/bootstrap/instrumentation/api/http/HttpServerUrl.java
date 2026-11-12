package datadog.trace.bootstrap.instrumentation.api.http;

import static datadog.trace.bootstrap.instrumentation.api.OtelHttpSemantics.forwardedValue;
import static datadog.trace.bootstrap.instrumentation.api.OtelHttpSemantics.parsePort;
import static datadog.trace.bootstrap.instrumentation.api.OtelHttpSemantics.portFromHost;
import static datadog.trace.bootstrap.instrumentation.api.OtelHttpSemantics.serverPort;
import static datadog.trace.bootstrap.instrumentation.api.URIUtils.decode;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import java.net.URI;

/** Captured server URL data shared by the Datadog and OpenTelemetry extractors. */
public abstract class HttpServerUrl {
  private HttpServerUrl() {}

  public static HttpServerUrl fromRequest(
      URIDataAdapter uri, String path, AgentSpanContext.Extracted extracted, String hostHeader) {
    return new Request(uri, path, extracted, hostHeader);
  }

  public static HttpServerUrl fromTransaction(String originalUrl, String host, String port) {
    return new Transaction(originalUrl, host, port);
  }

  public static HttpServerUrl fromInferredProxy(String domain, String path) {
    return new InferredProxy(domain, path);
  }

  abstract CharSequence datadogUrl();

  String datadogHostname() {
    return null;
  }

  String fragment() {
    return null;
  }

  boolean isValid() {
    return true;
  }

  abstract String path();

  abstract String query();

  abstract Origin origin();

  /** The effective origin, resolved independently of client-IP collection. */
  static final class Origin {
    final String scheme;
    final String address;
    final int port;

    private Origin(String scheme, String address, int port) {
      this.scheme = scheme;
      this.address = address;
      this.port = port;
    }

    private static Origin resolve(
        String scheme, String host, int requestPort, String forwardedHost, String forwardedPort) {
      forwardedHost = forwardedValue(forwardedHost);
      int hostPort = -1;
      if (forwardedHost != null) {
        host = forwardedHost;
        if (forwardedHost.charAt(0) == '[') {
          int closingBracket = forwardedHost.indexOf(']');
          if (closingBracket > 0) {
            host = forwardedHost.substring(1, closingBracket);
            if (closingBracket + 1 < forwardedHost.length()
                && forwardedHost.charAt(closingBracket + 1) == ':') {
              hostPort = parsePort(forwardedHost.substring(closingBracket + 2));
            }
          }
        } else {
          int colon = forwardedHost.lastIndexOf(':');
          if (colon > 0 && colon == forwardedHost.indexOf(':')) {
            host = forwardedHost.substring(0, colon);
            hostPort = parsePort(forwardedHost.substring(colon + 1));
          }
        }
      }
      int port = parsePort(forwardedValue(forwardedPort));
      if (port <= 0) {
        port = hostPort;
      }
      if (port <= 0) {
        port = forwardedHost == null && requestPort > 0 ? requestPort : serverPort(scheme, -1);
      }
      return new Origin(scheme, host, port);
    }
  }

  private static final class Request extends HttpServerUrl {
    private final URIDataAdapter uri;
    private final String path;
    private final AgentSpanContext.Extracted extracted;
    private final String hostHeader;

    private Request(
        URIDataAdapter uri, String path, AgentSpanContext.Extracted extracted, String hostHeader) {
      this.uri = uri;
      this.path = path;
      this.extracted = extracted;
      this.hostHeader = hostHeader;
    }

    @Override
    CharSequence datadogUrl() {
      if (uri.isValid()) {
        return URIUtils.lazyValidURL(uri.scheme(), uri.host(), uri.port(), path);
      }
      return uri.supportsRaw() ? URIUtils.lazyInvalidUrl(uri.raw()) : null;
    }

    @Override
    String datadogHostname() {
      String forwardedHost = extracted == null ? null : extracted.getXForwardedHost();
      return forwardedHost != null ? forwardedHost : uri.host();
    }

    @Override
    boolean isValid() {
      return uri.isValid();
    }

    @Override
    String path() {
      if (uri.isValid()) {
        return path == null || path.isEmpty() ? "/" : path.charAt(0) == '/' ? path : "/" + path;
      }
      String raw = rawWithoutFragment();
      int queryStart = raw == null ? -1 : raw.indexOf('?');
      return queryStart < 0 ? raw : raw.substring(0, queryStart);
    }

    @Override
    String query() {
      if (uri.isValid()) {
        if (!uri.supportsRaw()) {
          return uri.query();
        }
        return Config.get().isHttpServerRawQueryString()
            ? uri.rawQuery()
            : URIUtils.preserveQueryDelimiters(uri.rawQuery(), uri.query());
      }
      String raw = rawWithoutFragment();
      int queryStart = raw == null ? -1 : raw.indexOf('?');
      if (queryStart < 0) {
        return null;
      }
      String query = raw.substring(queryStart + 1);
      return Config.get().isHttpServerRawQueryString()
          ? query
          : URIUtils.preserveQueryDelimiters(query, decode(query, uri.hasPlusEncodedSpaces()));
    }

    private String rawWithoutFragment() {
      String raw = uri.supportsRaw() ? uri.raw() : null;
      int fragmentStart = raw == null ? -1 : raw.indexOf('#');
      return fragmentStart < 0 ? raw : raw.substring(0, fragmentStart);
    }

    @Override
    String fragment() {
      return uri.fragment();
    }

    @Override
    Origin origin() {
      String scheme = forwardedValue(extracted == null ? null : extracted.getXForwardedProto());
      if (scheme == null) {
        scheme = uri.scheme();
      }
      int port = uri.port();
      if (scheme != null && !scheme.equalsIgnoreCase(uri.scheme())) {
        port = portFromHost(hostHeader);
      }
      return Origin.resolve(
          scheme,
          uri.host(),
          port,
          extracted == null ? null : extracted.getXForwardedHost(),
          extracted == null ? null : extracted.getXForwardedPort());
    }
  }

  private static final class InferredProxy extends HttpServerUrl {
    private final String domain;
    private final String path;

    private InferredProxy(String domain, String path) {
      this.domain = domain != null && !domain.isEmpty() ? domain : null;
      this.path = path;
    }

    @Override
    CharSequence datadogUrl() {
      return domain != null ? "https://" + domain + path : path;
    }

    @Override
    String path() {
      return path;
    }

    @Override
    String query() {
      return null;
    }

    @Override
    Origin origin() {
      return new Origin(domain != null ? "https" : null, domain, domain != null ? 443 : -1);
    }
  }

  private static final class Transaction extends HttpServerUrl {
    private final String originalUrl;
    private final String host;
    private final String port;
    private boolean parsed;
    private URI uri;

    private Transaction(String originalUrl, String host, String port) {
      this.originalUrl = originalUrl;
      this.host = host;
      this.port = port;
    }

    private URI uri() {
      if (!parsed) {
        if (originalUrl != null) {
          try {
            uri = URI.create(originalUrl);
          } catch (IllegalArgumentException ignored) {
            // Keep the separately captured host and port when the transaction URL is malformed.
          }
        }
        parsed = true;
      }
      return uri;
    }

    @Override
    CharSequence datadogUrl() {
      return originalUrl;
    }

    @Override
    String path() {
      URI uri = uri();
      return uri == null ? null : uri.getPath();
    }

    @Override
    String query() {
      URI uri = uri();
      return uri == null ? null : uri.getRawQuery();
    }

    @Override
    Origin origin() {
      URI uri = uri();
      String scheme = uri == null ? null : uri.getScheme();
      String address = host;
      int port = parsePort(this.port);
      if (uri != null) {
        if (address == null || address.isEmpty()) {
          address = uri.getHost();
        }
        if (port <= 0) {
          port = uri.getPort();
        }
      }
      return new Origin(scheme, address, serverPort(scheme, port));
    }
  }
}
