package com.datadog.iast.model;

public enum SourceType {
  REQUEST_QUERY_PARAMETER("http.url_details.queryString"),
  REQUEST_PATH("http.url_details.path");

  public final String key;

  SourceType(final String key) {
    this.key = key;
  }
}
