package com.datadog.iast.model;

public final class SourceType {

  private SourceType() {}

  public static final byte NONE = 0;

  public static final byte REQUEST_PARAMETER_NAME = 1;
  static final String REQUEST_PARAMETER_NAME_STRING = "http.request.parameter.name";
  public static final byte REQUEST_PARAMETER_VALUE = 2;
  static final String REQUEST_PARAMETER_VALUE_STRING = "http.request.parameter";
  public static final byte REQUEST_HEADER_NAME = 3;
  static final String REQUEST_HEADER_NAME_STRING = "http.request.header.name";
  public static final byte REQUEST_HEADER_VALUE = 4;
  static final String REQUEST_HEADER_VALUE_STRING = "http.request.header";
  public static final byte REQUEST_COOKIE_NAME = 5;
  static final String REQUEST_COOKIE_NAME_STRING = "http.request.cookie.name";
  public static final byte REQUEST_COOKIE_VALUE = 6;
  static final String REQUEST_COOKIE_VALUE_STRING = "http.request.cookie.value";
  public static final byte REQUEST_COOKIE_COMMENT = 7;
  static final String REQUEST_COOKIE_COMMENT_STRING = "http.request.cookie.comment";
  public static final byte REQUEST_COOKIE_DOMAIN = 8;
  static final String REQUEST_COOKIE_DOMAIN_STRING = "http.request.cookie.domain";
  public static final byte REQUEST_COOKIE_PATH = 9;
  static final String REQUEST_COOKIE_PATH_STRING = "http.request.cookie.path";

  public static String toString(final byte sourceType) {
    switch (sourceType) {
      case REQUEST_PARAMETER_NAME:
        return REQUEST_PARAMETER_NAME_STRING;
      case REQUEST_PARAMETER_VALUE:
        return REQUEST_PARAMETER_VALUE_STRING;
      case REQUEST_HEADER_NAME:
        return REQUEST_HEADER_NAME_STRING;
      case REQUEST_HEADER_VALUE:
        return REQUEST_HEADER_VALUE_STRING;
      case REQUEST_COOKIE_NAME:
        return REQUEST_COOKIE_NAME_STRING;
      case REQUEST_COOKIE_VALUE:
        return REQUEST_COOKIE_VALUE_STRING;
      case REQUEST_COOKIE_COMMENT:
        return REQUEST_COOKIE_COMMENT_STRING;
      case REQUEST_COOKIE_DOMAIN:
        return REQUEST_COOKIE_DOMAIN_STRING;
      case REQUEST_COOKIE_PATH:
        return REQUEST_COOKIE_PATH_STRING;
      default:
        return null;
    }
  }
}
