package datadog.trace.bootstrap.instrumentation.jdbc;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.normalize.SQLNormalizer;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.function.BiFunction;

public final class DBQueryInfo {

  private static final int MAX_SQL_LENGTH_TO_CACHE = 4096;

  private static final DDCache<String, DBQueryInfo> CACHED_PREPARED_STATEMENTS =
      DDCaches.newFixedSizeCache(512);

  private static final BiFunction<String, Boolean, DBQueryInfo> NORMALIZE = DBQueryInfo::new;

  public static DBQueryInfo ofStatement(String sql) {
    return ofStatement(sql, false);
  }

  public static DBQueryInfo ofStatement(String sql, boolean stripSQLComment) {
    return NORMALIZE.apply(sql, stripSQLComment);
  }

  public static DBQueryInfo ofPreparedStatement(String sql) {
    return ofPreparedStatement(sql, false);
  }

  public static DBQueryInfo ofPreparedStatement(String sql, boolean stripSQLComment) {
    if (sql.length() > MAX_SQL_LENGTH_TO_CACHE) {
      return NORMALIZE.apply(sql, stripSQLComment);
    } else {
      // if a prepared statement has a sql comment injected, we strip the comment
      // so, it does not get added to the span. This also prevents us from obfuscating more
      // than necessary.
      return CACHED_PREPARED_STATEMENTS.computeIfAbsent(
          sql, k -> NORMALIZE.apply(sql, stripSQLComment));
    }
  }

  private final UTF8BytesString operation;
  private final UTF8BytesString sql;

  public DBQueryInfo(String sql, boolean stripSQLComment) {
    this.sql = SQLNormalizer.normalize(sql, stripSQLComment);
    this.operation = UTF8BytesString.create(extractOperation(this.sql));
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
