package com.datadog.iast.model;

public final class SourceType {

  private SourceType() {}

  public static final byte NONE = 0;

  public static final byte REQUEST_QUERY_PARAMETER = 1;
  private static final String REQUEST_QUERY_PARAMETER_STRING = "http.url_details.queryString";
  public static final byte REQUEST_PATH = 2;
  private static final String REQUEST_PATH_STRING = "http.url_details.path";

  public static String toString(final byte sourceType) {
    switch (sourceType) {
      case REQUEST_QUERY_PARAMETER:
        return REQUEST_QUERY_PARAMETER_STRING;
      case REQUEST_PATH:
        return REQUEST_PATH_STRING;
      default:
        return null;
    }
  }
}
