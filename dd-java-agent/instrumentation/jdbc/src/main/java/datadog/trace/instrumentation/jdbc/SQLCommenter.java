package datadog.trace.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.BaseHash;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SQLCommenter {
  private static final Logger log = LoggerFactory.getLogger(SQLCommenter.class);
  private static final String UTF8 = StandardCharsets.UTF_8.toString();

  private static final char EQUALS = '=';
  private static final char COMMA = ',';
  private static final char QUOTE = '\'';
  private static final char SPACE = ' ';
  private static final String OPEN_COMMENT = "/*";
  private static final int OPEN_COMMENT_LEN = OPEN_COMMENT.length();
  private static final String CLOSE_COMMENT = "*/";

  // Injected fields. When adding a new one, be sure to update this and the methods below.
  private static final int NUMBER_OF_FIELDS = 9;
  private static final String PARENT_SERVICE = encode("ddps");
  private static final String DATABASE_SERVICE = encode("dddbs");
  private static final String DD_HOSTNAME = encode("ddh");
  private static final String DD_DB_NAME = encode("dddb");
  private static final String DD_PEER_SERVICE = "ddprs";
  private static final String DD_ENV = encode("dde");
  private static final String DD_VERSION = encode("ddpv");
  private static final String TRACEPARENT = encode("traceparent");
  private static final String DD_SERVICE_HASH = encode("ddsh");

  private static final int KEY_AND_SEPARATORS_ESTIMATED_SIZE = 10;
  private static final int VALUE_ESTIMATED_SIZE = 10;
  private static final int TRACE_PARENT_EXTRA_ESTIMATED_SIZE = 50;
  private static final int INJECTED_COMMENT_ESTIMATED_SIZE =
      NUMBER_OF_FIELDS * (KEY_AND_SEPARATORS_ESTIMATED_SIZE + VALUE_ESTIMATED_SIZE)
          + TRACE_PARENT_EXTRA_ESTIMATED_SIZE;

  protected static String getFirstWord(String sql) {
    int beginIndex = 0;
    while (beginIndex < sql.length() && Character.isWhitespace(sql.charAt(beginIndex))) {
      beginIndex++;
    }
    int endIndex = beginIndex;
    while (endIndex < sql.length() && !Character.isWhitespace(sql.charAt(endIndex))) {
      endIndex++;
    }
    return sql.substring(beginIndex, endIndex);
  }

  public static String inject(
      String sql,
      String dbService,
      String dbType,
      String hostname,
      String dbName,
      String traceParent,
      boolean preferAppend) {
    if (sql == null || sql.isEmpty()) {
      return sql;
    }
    boolean appendComment = preferAppend;
    if (dbType != null) {
      final String firstWord = getFirstWord(sql);

      // The Postgres JDBC parser doesn't allow SQL comments anywhere in a JDBC
      // callable statements
      // https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/java/org/postgresql/core/Parser.java#L1038
      // TODO: Could we inject the comment after the JDBC has been converted to
      // standard SQL?
      if (firstWord.startsWith("{") && dbType.startsWith("postgres")) {
        return sql;
      }

      // Append the comment for mysql JDBC callable statements
      if (firstWord.startsWith("{") && "mysql".equals(dbType)) {
        appendComment = true;
      }

      // Both Postgres and MySQL are unhappy with anything before CALL in a stored
      // procedure invocation, but they seem ok with it after so we force append mode
      if (firstWord.equalsIgnoreCase("call")) {
        appendComment = true;
      }

      // Append the comment in the case of a pg_hint_plan extension
      if (dbType.startsWith("postgres") && sql.contains("/*+")) {
        appendComment = true;
      }
    }
    if (hasDDComment(sql, appendComment)) {
      return sql;
    }

    Config config = Config.get();

    StringBuilder sb = new StringBuilder(sql.length() + INJECTED_COMMENT_ESTIMATED_SIZE);
    if (appendComment) {
      if (sql.trim().endsWith(";")) {
        sb.append(sql, 0, sql.lastIndexOf(";"));
      } else {
        sb.append(sql);
      }
      sb.append(SPACE);
    }

    sb.append(OPEN_COMMENT);
    int initSize = sb.length();
    append(sb, PARENT_SERVICE, config.getServiceName(), initSize);
    append(sb, DATABASE_SERVICE, dbService, initSize);
    append(sb, DD_HOSTNAME, hostname, initSize);
    append(sb, DD_DB_NAME, dbName, initSize);
    append(sb, DD_PEER_SERVICE, getPeerService(), initSize);
    append(sb, DD_ENV, config.getEnv(), initSize);
    append(sb, DD_VERSION, config.getVersion(), initSize);
    append(sb, TRACEPARENT, traceParent, initSize);
    if (config.isDbmInjectSqlBaseHash() && config.isExperimentalPropagateProcessTagsEnabled()) {
      append(sb, DD_SERVICE_HASH, BaseHash.getBaseHashStr(), initSize);
    }
    if (initSize == sb.length()) {
      // no comment was added
      return sql;
    }
    sb.append(CLOSE_COMMENT);
    if (!appendComment) {
      sb.append(SPACE);
      sb.append(sql);
    }

    if (appendComment && sql.trim().endsWith(";")) {
      sb.append(';');
    }

    return sb.toString();
  }

  private static String getPeerService() {
    AgentSpan span = activeSpan();
    Object peerService = null;
    if (span != null) {
      peerService = span.getTag(Tags.PEER_SERVICE);
    }
    return peerService != null ? peerService.toString() : null;
  }

  private static boolean hasDDComment(String sql, boolean appendComment) {
    if ((!sql.endsWith(CLOSE_COMMENT) && appendComment)
        || ((!sql.startsWith(OPEN_COMMENT)) && !appendComment)) {
      return false;
    }
    int startIdx = OPEN_COMMENT_LEN;
    if (appendComment) {
      startIdx += sql.lastIndexOf(OPEN_COMMENT);
    }
    return hasMatchingSubstring(sql, startIdx, PARENT_SERVICE)
        || hasMatchingSubstring(sql, startIdx, DATABASE_SERVICE)
        || hasMatchingSubstring(sql, startIdx, DD_HOSTNAME)
        || hasMatchingSubstring(sql, startIdx, DD_DB_NAME)
        || hasMatchingSubstring(sql, startIdx, DD_PEER_SERVICE)
        || hasMatchingSubstring(sql, startIdx, DD_ENV)
        || hasMatchingSubstring(sql, startIdx, DD_VERSION)
        || hasMatchingSubstring(sql, startIdx, TRACEPARENT)
        || hasMatchingSubstring(sql, startIdx, DD_SERVICE_HASH);
  }

  private static boolean hasMatchingSubstring(String sql, int startIndex, String substring) {
    boolean tooLong = startIndex + substring.length() >= sql.length();
    if (tooLong || !(sql.charAt(startIndex + substring.length()) == EQUALS)) {
      return false;
    }
    return sql.startsWith(substring, startIndex);
  }

  private static String encode(String val) {
    try {
      return URLEncoder.encode(val, UTF8);
    } catch (UnsupportedEncodingException exe) {
      if (log.isDebugEnabled()) {
        log.debug("exception thrown while encoding sql comment key %s", exe);
      }
    }
    return val;
  }

  private static void append(StringBuilder sb, String key, String value, int initSize) {
    if (null == value || value.isEmpty()) {
      return;
    }
    String encodedValue;
    try {
      encodedValue = URLEncoder.encode(value, UTF8);
    } catch (UnsupportedEncodingException e) {
      if (log.isDebugEnabled()) {
        log.debug("exception thrown while encoding sql comment %s", e);
      }
      return;
    }

    if (sb.length() > initSize) {
      sb.append(COMMA);
    }
    sb.append(key).append(EQUALS).append(QUOTE).append(encodedValue).append(QUOTE);
  }
}
