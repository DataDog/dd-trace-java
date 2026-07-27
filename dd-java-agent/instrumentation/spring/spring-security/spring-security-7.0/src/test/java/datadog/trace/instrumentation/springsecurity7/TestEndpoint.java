package datadog.trace.instrumentation.springsecurity7;

import java.net.URI;

public enum TestEndpoint {
  LOGIN("login", 302, ""),
  REGISTER("register", 200, ""),
  NOT_FOUND("not-found", 404, "not found"),
  UNKNOWN("", 451, null),
  CUSTOM("custom", 302, ""),
  SUCCESS("success", 200, ""),
  SDK("sdk", 200, "OK");

  private final String path;
  private final String rawPath;
  private final String query;
  private final String rawQuery;
  private final String fragment;
  private final int status;
  private final String body;

  TestEndpoint(String uri, int status, String body) {
    URI uriObj = URI.create(uri);
    this.path = uriObj.getPath();
    this.rawPath = uriObj.getRawPath();
    this.query = uriObj.getQuery();
    this.rawQuery = uriObj.getRawQuery();
    this.fragment = uriObj.getFragment();
    this.status = status;
    this.body = body;
  }

  public String getPath() {
    return "/" + path;
  }

  public String relativePath() {
    return path;
  }

  public String getRawPath() {
    return "/" + rawPath;
  }

  public String relativeRawPath() {
    return rawPath;
  }

  public String getQuery() {
    return query;
  }

  public String getRawQuery() {
    return rawQuery;
  }

  public String getFragment() {
    return fragment;
  }

  public int getStatus() {
    return status;
  }

  public String getBody() {
    return body;
  }

  public URI resolve(URI address) {
    return address.resolve(relativeRawPath());
  }
}
