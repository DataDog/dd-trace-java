package datadog.trace.bootstrap.otel.instrumentation.api.incubator.semconv.db;

/** Simple holder of sanitized statements. */
public final class SqlStatementInfo {

  private final String statement;
  private final String operation;
  private final String table;

  public static SqlStatementInfo create(String statement, String operation, String table) {
    return new SqlStatementInfo(statement, operation, table);
  }

  private SqlStatementInfo(String statement, String operation, String table) {
    this.statement = statement;
    this.operation = operation;
    this.table = table;
  }

  public String getFullStatement() {
    return statement;
  }

  public String getOperation() {
    return operation;
  }

  public String getMainIdentifier() {
    return table;
  }
}
