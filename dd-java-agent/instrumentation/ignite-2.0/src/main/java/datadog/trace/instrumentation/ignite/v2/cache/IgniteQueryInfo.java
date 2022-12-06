package datadog.trace.instrumentation.ignite.v2.cache;

import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.normalize.SQLNormalizer;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public final class IgniteQueryInfo {

  private static final DDCache<Pair<String, String>, IgniteQueryInfo> CACHED_PREPARED_STATEMENTS =
      DDCaches.newFixedSizeCache(512);

  private static final Function<Pair<String, String>, IgniteQueryInfo> NORMALIZE =
      // Uses inner class for predictable name for Instrumenter.Default.helperClassNames()
      new Function<Pair<String, String>, IgniteQueryInfo>() {
        @Override
        public IgniteQueryInfo apply(Pair<String, String> Pair) {
          return new IgniteQueryInfo(Pair.getLeft(), Pair.getRight());
        }
      };

  private static final UTF8BytesString DEFAULT_OPERATION = UTF8BytesString.create("SELECT");

  private static final Map<String, UTF8BytesString> VALID_DB_OPERATIONS;
  private static final Map<String, UTF8BytesString> HEALTH_CHECK_STATEMENTS =
      Collections.singletonMap("SELECT 1", UTF8BytesString.create("SELECT 1"));

  public static IgniteQueryInfo ofStatement(String sql) {
    return ofStatement(sql, null);
  }

  public static IgniteQueryInfo ofStatement(String sql, String type) {
    return new IgniteQueryInfo(sql, type);
  }

  public static IgniteQueryInfo ofPreparedStatement(String sql) {
    return ofPreparedStatement(sql, null);
  }

  public static IgniteQueryInfo ofPreparedStatement(String sql, String type) {
    return CACHED_PREPARED_STATEMENTS.computeIfAbsent(Pair.of(sql, type), NORMALIZE);
  }

  static {
    Map<String, UTF8BytesString> validDbOperations = new HashMap<>();
    for (String op :
        Arrays.asList(
            "SELECT",
            "INSERT",
            "DELETE",
            "UPDATE",
            "CREATE",
            "ALTER",
            "DROP",
            "TRUNCATE",
            "GRANT",
            "COMMIT",
            "REVOKE",
            "ROLLBACK",
            "SAVEPOINT",
            "WITH",
            "MERGE")) {
      validDbOperations.put(op, UTF8BytesString.create(op));
    }
    VALID_DB_OPERATIONS = Collections.unmodifiableMap(validDbOperations);
  }

  private final UTF8BytesString operation;
  private final UTF8BytesString sql;

  public IgniteQueryInfo(String sql, String type) {

    UTF8BytesString prospectiveOperation = VALID_DB_OPERATIONS.get(extractOperation(sql));
    boolean fragment = prospectiveOperation == null; // SQL fragment if not valid op
    this.operation = prospectiveOperation != null ? prospectiveOperation : DEFAULT_OPERATION;

    if (HEALTH_CHECK_STATEMENTS.containsKey(sql.toUpperCase())) {
      // No need to mask health checks
      this.sql = HEALTH_CHECK_STATEMENTS.get(sql);

    } else {

      if (!fragment) {
        this.sql = UTF8BytesString.create(SQLNormalizer.normalize(sql));

      } else {
        final StringBuilder sqlBuilder = new StringBuilder();

        sqlBuilder.append("SELECT *");

        if (type != null) {
          sqlBuilder.append(" FROM ");
          sqlBuilder.append(type);
        }

        sqlBuilder.append(" WHERE ");

        sqlBuilder.append(SQLNormalizer.normalize(sql));

        this.sql = UTF8BytesString.create(sqlBuilder);
      }
    }
  }

  public UTF8BytesString getOperation() {
    return operation;
  }

  public UTF8BytesString getSql() {
    return sql;
  }

  public static String extractOperation(CharSequence sql) {
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
      return sql.subSequence(start, firstWhitespace).toString().toUpperCase();
    }
    return null;
  }
}
