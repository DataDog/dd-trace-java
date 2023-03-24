package datadog.trace.instrumentation.jdbc;

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
  private static final String OPEN_COMMENT = "/*";
  private static final String CLOSE_COMMENT = "*/ ";
  private static final int INITIAL_CAPACITY = computeInitialCapacity();

  public static String inject(final String sql, final String dbService) {
    return inject(sql, dbService, null, false);
  }

  public static String inject(
      final String sql,
      final String dbService,
      final String traceParent,
      final boolean injectTrace) {
    if (sql == null || sql.isEmpty()) {
      return sql;
    }
    if (hasDDComment(sql)) {
      return sql;
    }
    final Config config = Config.get();
    final String parentService = config.getServiceName();
    final String env = config.getEnv();
    final String version = config.getVersion();
    final int commentSize = capacity(traceParent, parentService, dbService, env, version);
    StringBuilder sb = new StringBuilder(sql.length() + commentSize);
    sb.append(OPEN_COMMENT);
    toComment(sb, injectTrace, parentService, dbService, env, version, traceParent);
    if (sb.length() == 2) {
      return sql;
    }
    sb.append(CLOSE_COMMENT);
    sb.append(sql);
    return sb.toString();
  }

  private static boolean hasDDComment(String sql) {
    // first check to see if sql starts with a comment
    if (!(sql.startsWith("/*"))) {
      return false;
    }
    // else check to see if it's a DBM trace sql comment
    int i = 2;
    boolean found = false;
    if (sql.length() > 2) {
      // check if the next word starts with one of the specified keys
      if (sql.startsWith(PARENT_SERVICE, i) && hasMatchingSubstring(sql, i, PARENT_SERVICE)) {
        found = true;
      } else if (sql.startsWith(DATABASE_SERVICE, i)
          && hasMatchingSubstring(sql, i, DATABASE_SERVICE)) {
        found = true;
      } else if (sql.startsWith(DD_ENV, i) && hasMatchingSubstring(sql, i, DD_ENV)) {
        found = true;
      } else if (sql.startsWith(DD_VERSION, i) && hasMatchingSubstring(sql, i, DD_VERSION)) {
        found = true;
      } else if (sql.startsWith(TRACEPARENT, i) && hasMatchingSubstring(sql, i, TRACEPARENT)) {
        found = true;
      }
    }

    return found;
  }

  private static boolean hasMatchingSubstring(String str, int startIndex, String substring) {
    if (startIndex + substring.length() >= str.length()) {
      return false;
    }
    for (int i = 0; i < substring.length(); i++) {
      if (str.charAt(startIndex + i) != substring.charAt(i)) {
        return false;
      }
    }
    // check that the substring is followed by an equals sign
    return str.charAt(startIndex + substring.length()) == EQUALS;
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
      final boolean injectTrace,
      final String parentService,
      final String dbService,
      final String env,
      final String version,
      final String traceparent) {
    boolean first = false;
    first = append(sb, PARENT_SERVICE, parentService, first);
    first = append(sb, DATABASE_SERVICE, dbService, first);
    first = append(sb, DD_ENV, env, first);
    first = append(sb, DD_VERSION, version, first);
    if (injectTrace) {
      append(sb, TRACEPARENT, traceparent, first);
    }
  }

  private static boolean append(StringBuilder sb, String key, String value, boolean prependComma) {
    if (null != value && !value.isEmpty()) {
      try {
        if (prependComma) {
          sb.append(COMMA);
        }
        sb.append(key);
        sb.append(EQUALS);
        sb.append(QUOTE);
        sb.append(URLEncoder.encode(value, UTF8));
        sb.append(QUOTE);
      } catch (UnsupportedEncodingException e) {
        if (log.isDebugEnabled()) {
          log.debug("exception thrown while encoding sql comment %s", e);
        }
      }
    }
    if (sb.length() > 2) {
      prependComma = true;
    }
    return prependComma;
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
