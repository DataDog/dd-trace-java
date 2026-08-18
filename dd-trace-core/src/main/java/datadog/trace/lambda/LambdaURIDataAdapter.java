package datadog.trace.lambda;

import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase;
import java.util.Map;

/**
 * {@link datadog.trace.bootstrap.instrumentation.api.URIDataAdapter} implementation for Lambda
 * events, which expose the path and the query string separately rather than as a URI.
 */
class LambdaURIDataAdapter extends URIDataAdapterBase {
  private final String path;
  private final String query;
  private final String scheme;
  private final int port;

  LambdaURIDataAdapter(String pathWithQuery, Map<String, String> headers) {
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

    String forwardedProto = headers != null ? headers.get("x-forwarded-proto") : null;
    this.scheme = (forwardedProto != null && !forwardedProto.isEmpty()) ? forwardedProto : "https";

    String forwardedPort = headers != null ? headers.get("x-forwarded-port") : null;
    int parsedPort = -1;
    if (forwardedPort != null && !forwardedPort.isEmpty()) {
      try {
        parsedPort = Integer.parseInt(forwardedPort.trim());
      } catch (NumberFormatException ignored) {
      }
    }
    this.port = parsedPort > 0 ? parsedPort : 443;
  }

  @Override
  public String scheme() {
    return scheme;
  }

  @Override
  public String host() {
    return null;
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
