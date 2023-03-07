package datadog.trace.instrumentation.jdbc;

import static datadog.trace.instrumentation.jdbc.JDBCDecorator.SQL_COMMENT_INJECTION_FULL;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.SQL_COMMENT_INJECTION_STATIC;

import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SQLCommenter {

  private static final Logger log = LoggerFactory.getLogger(SQLCommenter.class);
  private static final String UTF8 = StandardCharsets.UTF_8.toString();
  protected static final String PARENT_SERVICE = "ddps";
  protected static final String DATABASE_SERVICE = "dddbs";
  protected static final String DD_ENV = "dde";
  protected static final String DD_VERSION = "ddpv";
  protected static final String TRACEPARENT = "traceparent";
  protected static final String W3C_CONTEXT_VERSION = "00";
  private static final char EQUALS = '=';
  private static final char COMMA = ',';
  private static final char QUOTE = '\'';
  private static final int COMMENT_CAPACITY = computeBuilderCapacity();
  public String commentedSQL;
  String injectionMode;
  String sql;
  DDTraceId traceId;
  long spanId;
  Integer samplingPriority;
  String dbService;

  public String getCommentedSQL() {
    return commentedSQL;
  }

  public Integer getSamplingPriority() {
    return samplingPriority;
  }

  public void setSamplingPriority(Integer samplingPriority) {
    this.samplingPriority = samplingPriority;
  }

  public SQLCommenter(final String injectionMode, final String sql, final String dbService) {
    this.injectionMode = injectionMode;
    this.sql = sql;
    this.dbService = dbService;
  }

  public SQLCommenter(
      final String injectionMode,
      final String sql,
      final String dbService,
      final DDTraceId traceId,
      final long spanId,
      final Integer samplingPriority) {
    this.injectionMode = injectionMode;
    this.sql = sql;
    this.dbService = dbService;
    this.traceId = traceId;
    this.spanId = spanId;
    this.samplingPriority = samplingPriority;
  }

  public SQLCommenter(
      final String injectionMode,
      final String sql,
      final String dbService,
      final DDTraceId traceId,
      final long spanId) {
    this.injectionMode = injectionMode;
    this.sql = sql;
    this.dbService = dbService;
    this.traceId = traceId;
    this.spanId = spanId;
  }

  public void inject() {
    if (this.sql == null || this.sql.isEmpty()) {
      this.commentedSQL = this.sql;
      return;
    }
    // If the SQL already has a comment, just return it.
    final byte[] sqlStmtBytes = this.sql.getBytes(StandardCharsets.UTF_8);
    if (hasDDComment(sqlStmtBytes)) {
      this.commentedSQL = this.sql;
      return;
    }

    final String comment =
        toComment(
            this.injectionMode, this.dbService, this.traceId, this.spanId, this.samplingPriority);
    if (comment.isEmpty()) {
      this.commentedSQL = this.sql;
      return;
    }

    int capacity = comment.length() + this.sql.length() + 5;
    ByteBuffer buffer = ByteBuffer.allocate(capacity);
    buffer.put("/*".getBytes(StandardCharsets.UTF_8));
    buffer.put(comment.getBytes(StandardCharsets.UTF_8));
    buffer.put("*/ ".getBytes(StandardCharsets.UTF_8));
    buffer.put(sqlStmtBytes);

    this.commentedSQL = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
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
    return arr[startIndex + substring.length()] == EQUALS;
  }

  protected static String toComment(
      final String injectionMode,
      final String dbService,
      final DDTraceId traceId,
      final long spanId,
      final Integer samplingPriority) {
    StringBuilder sb = new StringBuilder(COMMENT_CAPACITY);
    final Config config = Config.get();
    if (injectComment(injectionMode)) {
      append(sb, PARENT_SERVICE, config.getServiceName());
      append(sb, DATABASE_SERVICE, dbService);
      append(sb, DD_ENV, config.getEnv());
      append(sb, DD_VERSION, config.getVersion());
      if (injectionMode.equals(SQL_COMMENT_INJECTION_FULL)) {
        append(sb, TRACEPARENT, traceParent(traceId, spanId, samplingPriority));
      }
    }
    if (sb.length() > 0) {
      sb.deleteCharAt(sb.length() - 1); // remove trailing comma
    }
    return sb.toString();
  }

  private static void append(StringBuilder sb, String key, Object value) {
    if (!isBlank(value)) {
      try {
        sb.append(URLEncoder.encode(key, UTF8));
        sb.append(EQUALS);
        sb.append(QUOTE);
        sb.append(URLEncoder.encode(Objects.toString(value), UTF8));
        sb.append(QUOTE);
        sb.append(COMMA);
      } catch (UnsupportedEncodingException e) {
        if (log.isDebugEnabled()) {
          log.debug("exception thrown while encoding sql comment %s", e);
        }
      }
    }
  }

  private static boolean injectComment(String injectionMode) {
    return injectionMode.equals(SQL_COMMENT_INJECTION_FULL)
        || injectionMode.equals(SQL_COMMENT_INJECTION_STATIC);
  }

  private static int computeBuilderCapacity() {
    int tagKeysLen =
        PARENT_SERVICE.length()
            + DATABASE_SERVICE.length()
            + DD_ENV.length()
            + DD_VERSION.length()
            + TRACEPARENT.length();
    int extraCharsLen = 4 * 5 + 4; // two quotes, one equals & one comma + \* */
    int traceParentValueLen = 55;
    return tagKeysLen + extraCharsLen + traceParentValueLen;
  }

  private static boolean isBlank(Object obj) {
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

  private static String traceParent(DDTraceId traceId, long spanId, Integer priority) {
    long traceSampledFlag = 0L;
    if (priority != null && priority >= 1) {
      traceSampledFlag = 1L;
    }
    if (null == traceId) {
      return "";
    }
    return encodeTraceParent(Long.parseLong(traceId.toString()), spanId, traceSampledFlag);
  }

  protected static String encodeTraceParent(long traceID, long spanID, long sampled) {
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
}
