package datadog.trace.bootstrap.instrumentation.jdbc;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

public final class DBQueryInfo {

  private final UTF8BytesString operation;
  private final UTF8BytesString sql;

  public DBQueryInfo(UTF8BytesString sql) {
    this.sql = sql;
    this.operation = UTF8BytesString.create(extractOperation(sql));
  }

  public UTF8BytesString getOperation() {
    return operation;
  }

  public UTF8BytesString getSql() {
    return sql;
  }

  public static CharSequence extractOperation(CharSequence sql) {
    if (null == sql) {
      return null;
    }
    int start = 0;
    for (int i = 0; i < sql.length(); ++i) {
      if (Character.isAlphabetic(sql.charAt(i))) {
        start = i;
        break;
      }
    }
    int firstWhitespace = -1;
    for (int i = start; i < sql.length(); ++i) {
      char c = sql.charAt(i);
      if (Character.isWhitespace(c)) {
        firstWhitespace = i;
        break;
      }
    }
    if (firstWhitespace > -1) {
      return sql.subSequence(start, firstWhitespace);
    }
    return null;
  }
}
