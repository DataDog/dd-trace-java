package datadog.trace.api.iast;

public abstract class SourceTypes {

  private SourceTypes() {}

  public static final byte NONE = -1;

  public static final byte REQUEST_PARAMETER_NAME = 0;
  public static final byte REQUEST_PARAMETER_VALUE = 1;
  public static final byte REQUEST_HEADER_NAME = 2;
  public static final byte REQUEST_HEADER_VALUE = 3;
  public static final byte REQUEST_COOKIE_NAME = 4;
  public static final byte REQUEST_COOKIE_VALUE = 5;
  public static final byte REQUEST_BODY = 6;
  public static final byte REQUEST_QUERY = 7;
  public static final byte REQUEST_PATH_PARAMETER = 8;
  public static final byte REQUEST_MATRIX_PARAMETER = 9;
  public static final byte REQUEST_MULTIPART_PARAMETER = 10;
  public static final byte REQUEST_URI = 11;
  public static final byte REQUEST_PATH = 12;
  public static final byte GRPC_BODY = 13;

  /** Array indexed with all source types, the index should match the source types values */
  public static final String[] STRINGS = {
    "http.request.parameter.name",
    "http.request.parameter",
    "http.request.header.name",
    "http.request.header",
    "http.request.cookie.name",
    "http.request.cookie.value",
    "http.request.body",
    "http.request.query",
    "http.request.path.parameter",
    "http.request.matrix.parameter",
    "http.request.multipart.parameter",
    "http.request.uri",
    "http.request.path",
    "grpc.request.body"
  };

  public static String toString(final byte source) {
    return source < 0 ? null : STRINGS[source];
  }

  public static byte namedSource(final byte sourceType) {
    switch (sourceType) {
      case SourceTypes.REQUEST_PARAMETER_VALUE:
      case SourceTypes.REQUEST_PARAMETER_NAME:
        return SourceTypes.REQUEST_PARAMETER_NAME;
      case SourceTypes.REQUEST_HEADER_VALUE:
      case SourceTypes.REQUEST_HEADER_NAME:
        return SourceTypes.REQUEST_HEADER_NAME;
      case SourceTypes.REQUEST_COOKIE_VALUE:
      case SourceTypes.REQUEST_COOKIE_NAME:
        return SourceTypes.REQUEST_COOKIE_NAME;
      default:
        return sourceType;
    }
  }
}
