package datadog.trace.instrumentation.jdbc;

import static datadog.trace.instrumentation.jdbc.JDBCDecorator.SQL_COMMENT_INJECTION_FULL;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.SQL_COMMENT_INJECTION_STATIC;

import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class SQLCommenter {
  private static final String UTF8 = StandardCharsets.UTF_8.toString();
  private static final String PARENT_SERVICE = "ddps";
  private static final String DATABASE_SERVICE = "dddbs";
  private static final String DD_ENV = "dde";
  private static final String DD_VERSION = "ddpv";
  private static final String TRACEPARENT = "traceparent";
  public static final String W3C_CONTEXT_VERSION = "00";
  public String commentedSQL;

  public String getCommentedSQL() {
    return commentedSQL;
  }

  public static SQLCommenter.Builder newBuilder() {
    return new Builder();
  }

  /**
   * toComment takes a map of tags and creates a new sql comment using the sqlcommenter spec. This
   * is used to inject APM context into sql statements for APM<->DBM linking
   *
   * @param tags
   * @return String
   */
  public String toComment(final SortedMap<String, Object> tags) {
    final List<String> keyValuePairsList =
        tags.entrySet().stream()
            .filter(entry -> !isBlank(entry.getValue()))
            .map(
                entry -> {
                  try {
                    return String.format(
                        "%s='%s'",
                        urlEncode(entry.getKey()),
                        urlEncode(String.format("%s", entry.getValue())));
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                })
            .collect(Collectors.toList());
    StringBuilder sb = new StringBuilder();
    Iterator<String> iterator = keyValuePairsList.iterator();
    while (iterator.hasNext()) {
      sb.append(iterator.next());
      if (iterator.hasNext()) {
        sb.append(",");
      }
    }
    return sb.toString();
  }

  public String augmentSQLStatement(final String sqlStmt, final SortedMap<String, Object> tags) {
    if (sqlStmt == null || sqlStmt.isEmpty() || tags.isEmpty()) {
      return sqlStmt;
    }

    // If the SQL already has a comment, just return it.
    final byte[] sqlStmtBytes = sqlStmt.getBytes(StandardCharsets.UTF_8);
    if (hasDDComment(sqlStmtBytes)) {
      return sqlStmt;
    }

    String commentStr = toComment(tags);
    if (commentStr.isEmpty()) {
      return sqlStmt;
    }

    int capacity = commentStr.length() + sqlStmt.length() + 5;
    ByteBuffer buffer = ByteBuffer.allocate(capacity);
    buffer.put("/*".getBytes(StandardCharsets.UTF_8));
    buffer.put(commentStr.getBytes(StandardCharsets.UTF_8));
    buffer.put("*/ ".getBytes(StandardCharsets.UTF_8));
    buffer.put(sqlStmtBytes);

    return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
  }

  private boolean hasDDComment(byte[] sql) {
    // first check to see if sql starts with a comment
    if (sql.length < 1 || !(sql[0] == '/' && sql[1] == '*')) {
      return false;
    }

    // else check to see if it's a DBM trace sql comment
    int i = 2;
    boolean found = false;
    while (i < sql.length - 1) {
      // check if the next word starts with one of the specified keys
      if (sql[i] == PARENT_SERVICE.charAt(0) && hasMatchingSubstring(sql, i, PARENT_SERVICE)) {
        found = true;
        break;
      } else if (sql[i] == DATABASE_SERVICE.charAt(0)
          && hasMatchingSubstring(sql, i, DATABASE_SERVICE)) {
        found = true;
        break;
      } else if (sql[i] == DD_ENV.charAt(0) && hasMatchingSubstring(sql, i, DD_ENV)) {
        found = true;
        break;
      } else if (sql[i] == DD_VERSION.charAt(0) && hasMatchingSubstring(sql, i, DD_VERSION)) {
        found = true;
        break;
      } else if (sql[i] == TRACEPARENT.charAt(0) && hasMatchingSubstring(sql, i, TRACEPARENT)) {
        found = true;
        break;
      }
      // if none of the tags we set appear in the first word of the sql comment
      // we can safely assume, that this is a customer comment & we should move on
      break;
    }

    return found;
  }

  private boolean hasMatchingSubstring(byte[] arr, int startIndex, String substring) {
    if (startIndex + substring.length() >= arr.length) {
      return false;
    }
    for (int i = 0; i < substring.length(); i++) {
      if (arr[startIndex + i] != substring.charAt(i)) {
        return false;
      }
    }
    // check that the substring is followed by an equals sign
    return arr[startIndex + substring.length()] == '=';
  }

  private boolean isBlank(Object obj) {
    if (obj == null) {
      return true;
    }
    if (obj instanceof String) {
      return obj.equals("");
    }
    if (obj instanceof Number) {
      Number number = (Number) obj;
      return number.doubleValue() == 0.0;
    }
    return false;
  }

  private static String urlEncode(String s) throws Exception {
    return URLEncoder.encode(s, UTF8);
  }

  public SortedMap<String, Object> sortedKeyValuePairs(String dbInstance) {
    SortedMap<String, Object> sortedMap = new TreeMap<>();
    final Config config = Config.get();
    sortedMap.put(PARENT_SERVICE, config.getServiceName());
    sortedMap.put(DATABASE_SERVICE, dbInstance);
    sortedMap.put(DD_ENV, config.getEnv());
    sortedMap.put(DD_VERSION, config.getVersion());
    return sortedMap;
  }

  public SortedMap<String, Object> sortedKeyValuePairs(
      DDTraceId traceId, long spanId, Integer priority, String dbService) {
    SortedMap<String, Object> sortedMap = new TreeMap<>();
    final Config config = Config.get();
    sortedMap.put(PARENT_SERVICE, config.getServiceName());
    sortedMap.put(DATABASE_SERVICE, dbService);
    sortedMap.put(DD_ENV, config.getEnv());
    sortedMap.put(DD_VERSION, config.getVersion());
    sortedMap.put(TRACEPARENT, traceParent(traceId, spanId, priority));
    return sortedMap;
  }

  private String traceParent(DDTraceId traceId, long spanId, Integer priority) {
    long traceSampledFlag = 0L;
    if (priority != null && priority >= 1) {
      traceSampledFlag = 1L;
    }
    if (null == traceId) {
      return "";
    }
    return encodeTraceParent(Long.parseLong(traceId.toString()), spanId, traceSampledFlag);
  }

  public static String encodeTraceParent(long traceID, long spanID, long sampled) {
    ByteBuffer bb = ByteBuffer.allocate(55);
    bb.put(W3C_CONTEXT_VERSION.getBytes(StandardCharsets.US_ASCII));
    bb.put((byte) '-');
    String tid = String.format("%016x", traceID);
    for (int i = 0; i < 32 - tid.length(); i++) {
      bb.put((byte) '0');
    }
    bb.put(tid.getBytes(StandardCharsets.US_ASCII));
    bb.put((byte) '-');
    String sid = String.format("%016x", spanID);
    for (int i = 0; i < 16 - sid.length(); i++) {
      bb.put((byte) '0');
    }
    bb.put(sid.getBytes(StandardCharsets.US_ASCII));
    bb.put((byte) '-');
    bb.put((byte) '0');
    String sampledStr = Long.toHexString(sampled);
    bb.put(sampledStr.getBytes(StandardCharsets.US_ASCII));
    return new String(bb.array(), StandardCharsets.US_ASCII);
  }

  public static class Builder {
    String injectionMode;
    String sql;
    DDTraceId traceId;
    long spanId;
    Integer samplingPriority;
    String dbService;

    public Builder withInjectionMode(String injectionMode) {
      this.injectionMode = injectionMode;
      return this;
    }

    public Builder withSqlInput(String sql) {
      this.sql = sql;
      return this;
    }

    public Builder withTraceId(DDTraceId traceId) {
      this.traceId = traceId;
      return this;
    }

    public Builder withSpanId(long spanId) {
      this.spanId = spanId;
      return this;
    }

    public Builder withSamplingPriority(Integer samplingPriority) {
      this.samplingPriority = samplingPriority;
      return this;
    }

    public Builder withDbService(String dbService) {
      this.dbService = dbService;
      return this;
    }

    public SQLCommenter build() {
      SQLCommenter commenter = new SQLCommenter();
      SortedMap<String, Object> tags = new TreeMap<>();
      if (this.injectionMode.equals(SQL_COMMENT_INJECTION_STATIC)) {
        tags = commenter.sortedKeyValuePairs(String.valueOf(this.dbService));

      } else if (this.injectionMode.equals(SQL_COMMENT_INJECTION_FULL)) {
        tags =
            commenter.sortedKeyValuePairs(
                this.traceId, this.spanId, this.samplingPriority, String.valueOf(this.dbService));
      }
      commenter.commentedSQL = commenter.augmentSQLStatement(this.sql, tags);
      return commenter;
    }
  }
}
