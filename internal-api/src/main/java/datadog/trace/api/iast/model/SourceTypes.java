package datadog.trace.api.iast.model;

public abstract class SourceTypes {

  private SourceTypes() {}

  public static final String REQUEST_PARAMETER_NAME = "http.request.parameter.name";
  public static final String REQUEST_PARAMETER_VALUE = "http.request.parameter";
  public static final String REQUEST_HEADER_NAME = "http.request.header.name";
  public static final String REQUEST_HEADER_VALUE = "http.request.header";
  public static final String REQUEST_COOKIE_NAME = "http.request.cookie.name";
  public static final String REQUEST_COOKIE_VALUE = "http.request.cookie.value";
  public static final String REQUEST_COOKIE_COMMENT = "http.request.cookie.comment";
  public static final String REQUEST_COOKIE_DOMAIN = "http.request.cookie.domain";
  public static final String REQUEST_COOKIE_PATH = "http.request.cookie.path";
  public static final String REQUEST_BODY = "http.request.body";
}
