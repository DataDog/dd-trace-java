package datadog.trace.lambda;

import static datadog.trace.lambda.LambdaEventParser.findHeader;

import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase;
import java.util.Locale;
import java.util.Map;

/**
 * {@link datadog.trace.bootstrap.instrumentation.api.URIDataAdapter} implementation for Lambda
 * events, which expose the path and the query string separately rather than as a URI. Shared by the
 * WAF path, which needs the raw URI, and by the HTTP span tags, which need the URL.
 *
 * <p>Lambda events carry no scheme or port either, so both are derived from the {@code
 * x-forwarded-*} headers, defaulting to {@code https} and to the scheme's default port.
 *
 * <p>Nothing is percent-decoded, so {@code path()} and {@code query()} return the raw strings and
 * the {@code raw.resource} / {@code raw.query-string} settings have no effect.
 */
class LambdaURIDataAdapter extends URIDataAdapterBase {
  private final String path;
  private final String query;
  private final String scheme;
  private final String host;
  private final int port;

  LambdaURIDataAdapter(String pathWithQuery, Map<String, String> headers, String host) {
    if (pathWithQuery != null) {
      int queryIndex = pathWithQuery.indexOf('?');
      if (queryIndex != -1) {
        this.path = pathWithQuery.substring(0, queryIndex);
        this.query = pathWithQuery.substring(queryIndex + 1);
      } else {
        this.path = pathWithQuery;
        this.query = null;
      }
    } else {
      this.path = "/";
      this.query = null;
    }

    this.host = host;

    // Lowercased because the port default below and URIUtils.buildURL both compare the scheme
    // exactly; whitelisted because X-Forwarded-Proto is client-influenceable and arrives
    // comma-joined when duplicated, which would render as "https, http://host/path".
    String forwardedProto = findHeader(headers, "x-forwarded-proto");
    String proto = forwardedProto == null ? null : forwardedProto.toLowerCase(Locale.ROOT);
    this.scheme = "http".equals(proto) || "https".equals(proto) ? proto : "https";

    String forwardedPort = findHeader(headers, "x-forwarded-port");
    int parsedPort = -1;
    if (forwardedPort != null && !forwardedPort.isEmpty()) {
      try {
        parsedPort = Integer.parseInt(forwardedPort.trim());
      } catch (NumberFormatException ignored) {
      }
    }
    // URIUtils.buildURL only suppresses the port for 80 on http and 443 on https, so the default
    // has to follow the scheme or an http URL would leak ":443".
    this.port = parsedPort > 0 ? parsedPort : ("http".equals(this.scheme) ? 80 : 443);
  }

  @Override
  public String scheme() {
    return scheme;
  }

  @Override
  public String host() {
    return host;
  }

  @Override
  public int port() {
    return port;
  }

  @Override
  public String path() {
    return path;
  }

  @Override
  public String fragment() {
    return null;
  }

  @Override
  public String query() {
    return query;
  }

  @Override
  public boolean supportsRaw() {
    return true;
  }

  @Override
  public String rawPath() {
    return path;
  }

  @Override
  public String rawQuery() {
    return query;
  }
}
