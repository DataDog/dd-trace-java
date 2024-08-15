package datadog.trace.bootstrap.otel.instrumentation.api.incubator.semconv.db;

import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;

/** Redirects requests to our own {@link DBQueryInfo} sanitizer. */
public final class SqlStatementSanitizer {

  public static SqlStatementSanitizer create(boolean sanitizationEnabled) {
    return new SqlStatementSanitizer(sanitizationEnabled);
  }

  private final boolean sanitizationEnabled;

  private SqlStatementSanitizer(boolean sanitizationEnabled) {
    this.sanitizationEnabled = sanitizationEnabled;
  }

  public SqlStatementInfo sanitize(String statement) {
    if (sanitizationEnabled) {
      DBQueryInfo dbQueryInfo = DBQueryInfo.ofPreparedStatement(statement);
      return SqlStatementInfo.create(
          dbQueryInfo.getSql().toString(), dbQueryInfo.getOperation().toString(), null);
    } else {
      return SqlStatementInfo.create(statement, null, null);
    }
  }
}
