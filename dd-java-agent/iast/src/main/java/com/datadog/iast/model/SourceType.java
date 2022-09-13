package com.datadog.iast.model;

public final class SourceType {

  private SourceType() {}

  public static final byte NONE = 0;

  public static final byte REQUEST_QUERY_PARAMETER = 1;
  static final String REQUEST_QUERY_PARAMETER_STRING = "http.url_details.queryString";
  public static final byte REQUEST_PATH = 2;
  static final String REQUEST_PATH_STRING = "http.url_details.path";
  public static final byte REQUEST_PARAMETER_NAME = 3;
  static final String REQUEST_PARAMETER_NAME_STRING = "http.param.name";
  public static final byte REQUEST_PARAMETER_VALUE = 4;
  static final String REQUEST_PARAMETER_VALUE_STRING = "http.param.value";

  public static String toString(final byte sourceType) {
    switch (sourceType) {
      case REQUEST_QUERY_PARAMETER:
        return REQUEST_QUERY_PARAMETER_STRING;
      case REQUEST_PATH:
        return REQUEST_PATH_STRING;
      case REQUEST_PARAMETER_NAME:
        return REQUEST_PARAMETER_NAME_STRING;
      case REQUEST_PARAMETER_VALUE:
        return REQUEST_PARAMETER_VALUE_STRING;
      default:
        return null;
    }
  }
}
