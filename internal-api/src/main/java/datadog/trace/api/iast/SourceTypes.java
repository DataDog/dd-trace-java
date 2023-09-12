package datadog.trace.api.iast;

public abstract class SourceTypes {

  private SourceTypes() {}

  public static final byte NONE = -1;

  public static final byte REQUEST_PARAMETER_NAME = 0;
  public static final String REQUEST_PARAMETER_NAME_STRING = "http.request.parameter.name";
  public static final byte REQUEST_PARAMETER_VALUE = 1;
  public static final String REQUEST_PARAMETER_VALUE_STRING = "http.request.parameter";
  public static final byte REQUEST_HEADER_NAME = 2;
  public static final String REQUEST_HEADER_NAME_STRING = "http.request.header.name";
  public static final byte REQUEST_HEADER_VALUE = 3;
  public static final String REQUEST_HEADER_VALUE_STRING = "http.request.header";
  public static final byte REQUEST_COOKIE_NAME = 4;
  public static final String REQUEST_COOKIE_NAME_STRING = "http.request.cookie.name";
  public static final byte REQUEST_COOKIE_VALUE = 5;
  public static final String REQUEST_COOKIE_VALUE_STRING = "http.request.cookie.value";
  public static final byte REQUEST_BODY = 6;
  public static final String REQUEST_BODY_STRING = "http.request.body";
  public static final byte REQUEST_QUERY = 7;
  public static final String REQUEST_QUERY_STRING = "http.request.query";
  public static final byte REQUEST_PATH_PARAMETER = 8;
  public static final String REQUEST_PATH_PARAMETER_STRING = "http.request.path.parameter";
  public static final byte REQUEST_MATRIX_PARAMETER = 9;
  public static final String REQUEST_MATRIX_PARAMETER_STRING = "http.request.matrix.parameter";

  private static final byte[] VALUES = {
    REQUEST_PARAMETER_NAME,
    REQUEST_PARAMETER_VALUE,
    REQUEST_HEADER_NAME,
    REQUEST_HEADER_VALUE,
    REQUEST_COOKIE_NAME,
    REQUEST_COOKIE_VALUE,
    REQUEST_BODY,
    REQUEST_QUERY,
    REQUEST_PATH_PARAMETER,
    REQUEST_MATRIX_PARAMETER
  };

  public static byte[] values() {
    return VALUES;
  }

  public static String toString(final byte sourceType) {
    switch (sourceType) {
      case SourceTypes.REQUEST_PARAMETER_NAME:
        return SourceTypes.REQUEST_PARAMETER_NAME_STRING;
      case SourceTypes.REQUEST_PARAMETER_VALUE:
        return SourceTypes.REQUEST_PARAMETER_VALUE_STRING;
      case SourceTypes.REQUEST_HEADER_NAME:
        return SourceTypes.REQUEST_HEADER_NAME_STRING;
      case SourceTypes.REQUEST_HEADER_VALUE:
        return SourceTypes.REQUEST_HEADER_VALUE_STRING;
      case SourceTypes.REQUEST_COOKIE_NAME:
        return SourceTypes.REQUEST_COOKIE_NAME_STRING;
      case SourceTypes.REQUEST_COOKIE_VALUE:
        return SourceTypes.REQUEST_COOKIE_VALUE_STRING;
      case SourceTypes.REQUEST_BODY:
        return SourceTypes.REQUEST_BODY_STRING;
      case SourceTypes.REQUEST_QUERY:
        return SourceTypes.REQUEST_QUERY_STRING;
      case SourceTypes.REQUEST_PATH_PARAMETER:
        return SourceTypes.REQUEST_PATH_PARAMETER_STRING;
      case SourceTypes.REQUEST_MATRIX_PARAMETER:
        return SourceTypes.REQUEST_MATRIX_PARAMETER_STRING;
      default:
        return null;
    }
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
