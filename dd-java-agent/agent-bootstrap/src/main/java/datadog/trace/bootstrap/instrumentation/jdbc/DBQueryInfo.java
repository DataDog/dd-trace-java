package datadog.trace.bootstrap.instrumentation.jdbc;

import datadog.trace.api.Config;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.normalize.SQLNormalizer;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import java.util.HashMap;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class DBQueryInfo {

  private static final int COMBINED_SQL_LIMIT = 2 * 1024 * 1024; // characters

  private static final ToIntFunction<DBQueryInfo> SQL_WEIGHER = DBQueryInfo::weight;
  private static final DDCache<String, DBQueryInfo> CACHED_PREPARED_STATEMENTS =
      DDCaches.newFixedSizeWeightedCache(512, SQL_WEIGHER, COMBINED_SQL_LIMIT);
  private static final Function<String, DBQueryInfo> NORMALIZE = DBQueryInfo::new;

  public static DBQueryInfo ofStatement(String sql) {
    return NORMALIZE.apply(sql);
  }

  public static DBQueryInfo ofPreparedStatement(String sql) {
    return CACHED_PREPARED_STATEMENTS.computeIfAbsent(sql, NORMALIZE);
  }

  private final UTF8BytesString operation;
  private final UTF8BytesString sql;
  private Map<Integer, String> vals;
  private UTF8BytesString originSql;

  public boolean SqlObfuscation = Config.get().getJdbcSqlObfuscation();

  public DBQueryInfo(String sql) {
    this.sql = SQLNormalizer.normalize(sql);

    if (SqlObfuscation) {
      this.originSql = UTF8BytesString.create(sql.getBytes(UTF_8));
    } else {
      this.originSql = UTF8BytesString.EMPTY;
    }
    this.vals = new HashMap<>();
    this.operation = UTF8BytesString.create(extractOperation(this.sql));
  }

  public UTF8BytesString getOperation() {
    return operation;
  }

  public Map<Integer, String> getVals() {
    return vals;
  }

  public void setVal(int index, String val) {
    vals.put(index, val);
  }

  public UTF8BytesString getSql() {
    return sql;
  }


  int weight() {
    return sql.length();
  }
  public UTF8BytesString getOriginSql() {
    return originSql;
  }

  public static CharSequence extractOperation(CharSequence sql) {
    if (null == sql) {
      return null;
    }
    int start = 0;
    boolean insideComment = false;
    for (int i = 0; i < sql.length(); ++i) {
      char c = sql.charAt(i);
      if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
        insideComment = true;
        i++;
        continue;
      }
      if (c == '*' && i + 1 < sql.length() && sql.charAt(i + 1) == '/') {
        insideComment = false;
        i++;
        continue;
      }
      if (!insideComment && Character.isAlphabetic(c)) {
        start = i;
        break;
      }
    }

    int firstWhitespace = -1;
    for (int i = start; i < sql.length(); ++i) {
      char c = sql.charAt(i);
      if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
        insideComment = true;
        i++;
        continue;
      }
      if (c == '*' && i + 1 < sql.length() && sql.charAt(i + 1) == '/') {
        insideComment = false;
        i++;
        continue;
      }
      if (!insideComment && Character.isWhitespace(c)) {
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
