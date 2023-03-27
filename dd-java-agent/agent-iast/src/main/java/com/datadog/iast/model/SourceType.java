package com.datadog.iast.model;

import datadog.trace.api.iast.model.SourceTypes;

public final class SourceType {

  private SourceType() {}

  public static final byte NONE = 0;

  public static final byte REQUEST_PARAMETER_NAME = 1;
  public static final byte REQUEST_PARAMETER_VALUE = 2;
  public static final byte REQUEST_HEADER_NAME = 3;
  public static final byte REQUEST_HEADER_VALUE = 4;
  public static final byte REQUEST_COOKIE_NAME = 5;
  public static final byte REQUEST_COOKIE_VALUE = 6;
  public static final byte REQUEST_COOKIE_COMMENT = 7;
  public static final byte REQUEST_COOKIE_DOMAIN = 8;
  public static final byte REQUEST_COOKIE_PATH = 9;
  public static final byte REQUEST_BODY = 10;

  public static String toString(final byte sourceType) {
    switch (sourceType) {
      case REQUEST_PARAMETER_NAME:
        return SourceTypes.REQUEST_PARAMETER_NAME;
      case REQUEST_PARAMETER_VALUE:
        return SourceTypes.REQUEST_PARAMETER_VALUE;
      case REQUEST_HEADER_NAME:
        return SourceTypes.REQUEST_HEADER_NAME;
      case REQUEST_HEADER_VALUE:
        return SourceTypes.REQUEST_HEADER_VALUE;
      case REQUEST_COOKIE_NAME:
        return SourceTypes.REQUEST_COOKIE_NAME;
      case REQUEST_COOKIE_VALUE:
        return SourceTypes.REQUEST_COOKIE_VALUE;
      case REQUEST_COOKIE_COMMENT:
        return SourceTypes.REQUEST_COOKIE_COMMENT;
      case REQUEST_COOKIE_DOMAIN:
        return SourceTypes.REQUEST_COOKIE_DOMAIN;
      case REQUEST_COOKIE_PATH:
        return SourceTypes.REQUEST_COOKIE_PATH;
      case REQUEST_BODY:
        return SourceTypes.REQUEST_BODY;
      default:
        return null;
    }
  }
}
