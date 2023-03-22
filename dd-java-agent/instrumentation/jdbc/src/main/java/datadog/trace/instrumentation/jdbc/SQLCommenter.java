package datadog.trace.instrumentation.jdbc;

import static datadog.trace.instrumentation.jdbc.JDBCDecorator.SQL_COMMENT_INJECTION_FULL;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.SQL_COMMENT_INJECTION_STATIC;

import datadog.trace.api.Config;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SQLCommenter {

  private static final Logger log = LoggerFactory.getLogger(SQLCommenter.class);
  private static final String UTF8 = StandardCharsets.UTF_8.toString();
  private static final String PARENT_SERVICE = encode("ddps");
  private static final String DATABASE_SERVICE = encode("dddbs");
  private static final String DD_ENV = encode("dde");
  private static final String DD_VERSION = encode("ddpv");
  private static final String TRACEPARENT = encode("traceparent");
  private static final char EQUALS = '=';
  private static final char COMMA = ',';
  private static final char QUOTE = '\'';
  private static final String SPACE = " ";
  private static final String OPEN_COMMENT = "/*";
  private static final String CLOSE_COMMENT = "*/ ";
  private static final int INITIAL_CAPACITY = computeInitialCapacity();
  String commentedSQL;
  String injectionMode;
  String sql;
  String dbService;
  String traceparent;

  public String getCommentedSQL() {
    return commentedSQL;
  }

  public void setTraceparent(String traceparent) {
    this.traceparent = traceparent;
  }

  public SQLCommenter(final String injectionMode, final String sql, final String dbService) {
    this.injectionMode = injectionMode;
    this.sql = sql;
    this.dbService = dbService;
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
    final Config config = Config.get();
    final String parentService = config.getServiceName();
    final String env = config.getEnv();
    final String version = config.getVersion();
    final int commentSize = capacity(this.traceparent, parentService, this.dbService, env, version);
    StringBuilder sb = new StringBuilder(this.sql.length() + commentSize);
    toComment(
        sb, this.injectionMode, parentService, this.dbService, env, version, this.traceparent);
    if (sb.length() == 0) {
      this.commentedSQL = this.sql;
      return;
    }
    sb.append(SPACE);
    sb.append(this.sql);
    this.commentedSQL = sb.toString();
  }

  private static boolean hasDDComment(byte[] sql) {
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

  private static boolean hasMatchingSubstring(byte[] arr, int startIndex, String substring) {
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

  private static String encode(final String val) {
    try {
      return URLEncoder.encode(val, UTF8);
    } catch (UnsupportedEncodingException exe) {
      if (log.isDebugEnabled()) {
        log.debug("exception thrown while encoding sql comment key %s", exe);
      }
    }
    return val;
  }

  protected static void toComment(
      StringBuilder sb,
      final String injectionMode,
      final String parentService,
      final String dbService,
      final String env,
      final String version,
      final String traceparent) {
    if (injectComment(injectionMode)) {
      append(sb, PARENT_SERVICE, parentService);
      append(sb, DATABASE_SERVICE, dbService);
      append(sb, DD_ENV, env);
      append(sb, DD_VERSION, version);
      if (injectionMode.equals(SQL_COMMENT_INJECTION_FULL)) {
        append(sb, TRACEPARENT, traceparent);
      }
    }
    if (sb.length() > 0) {
      // remove the trailing comment
      sb.deleteCharAt(sb.length() - 1);
    }
  }

  private static void append(StringBuilder sb, String key, String value) {
    if (null != value && !value.isEmpty()) {
      try {
        sb.append(URLEncoder.encode(key, UTF8));
        sb.append(EQUALS);
        sb.append(QUOTE);
        sb.append(URLEncoder.encode(value, UTF8));
        sb.append(QUOTE);
        sb.append(COMMA);
      } catch (UnsupportedEncodingException e) {
        if (log.isDebugEnabled()) {
          log.debug("exception thrown while encoding sql comment %s", e);
        }
      }
    }
  }

  private static int capacity(
      final String traceparent,
      final String parentService,
      final String dbService,
      final String env,
      final String version) {
    int len = INITIAL_CAPACITY;
    if (null != traceparent) {
      len += traceparent.length();
    }
    if (null != parentService) {
      len += parentService.length();
    }
    if (null != dbService) {
      len += dbService.length();
    }
    if (null != env) {
      len += env.length();
    }
    if (null != version) {
      len += version.length();
    }
    return len;
  }

  private static boolean injectComment(String injectionMode) {
    return injectionMode.equals(SQL_COMMENT_INJECTION_FULL)
        || injectionMode.equals(SQL_COMMENT_INJECTION_STATIC);
  }

  private static int computeInitialCapacity() {
    int tagKeysLen =
        PARENT_SERVICE.length()
            + DATABASE_SERVICE.length()
            + DD_ENV.length()
            + DD_VERSION.length()
            + TRACEPARENT.length();
    int extraCharsLen =
        4 * 5
            + OPEN_COMMENT.length()
            + CLOSE_COMMENT.length(); // two quotes, one equals & one comma * 5 + \* */
    return tagKeysLen + extraCharsLen;
  }
}
