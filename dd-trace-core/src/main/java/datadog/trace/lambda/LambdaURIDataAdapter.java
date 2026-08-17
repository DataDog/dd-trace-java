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
 * x-forwarded-*} headers, defaulting to {@code https} and to the scheme's default port. The scheme
 * is normalised and whitelisted first, since the header is client-influenceable. Note that the
 * Lambda Extension ignores those headers and hardcodes {@code https} with no port, so the two agree
 * on every https trigger and this one is additionally right on an ALB http listener, which the
 * extension does not tag at all. Neither {@code rawPath()} nor {@code rawQuery()} differs from its
 * decoded counterpart: percent-decoding is not implemented.
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

    // Lowercased and whitelisted rather than used verbatim. Lowercased because the port default
    // below and URIUtils.buildURL both compare the scheme exactly, so an "HTTPS" would defeat the
    // default-port suppression. Whitelisted because the value is client-influenceable: Lambda
    // flattens duplicate request headers into a single comma-joined value, so a client-supplied
    // X-Forwarded-Proto arrives as "https, http" and would render as "https, http://host/path" in
    // http.url and in the raw URI handed to the WAF.
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
    // The default has to follow the scheme: URIUtils.buildURL only suppresses the port for 80 on
    // http and 443 on https, so defaulting to 443 under an http scheme would leak ":443" into
    // http.url.
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
