package datadog.trace.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.common.container.ContainerInfo;
import datadog.trace.api.Config;
import datadog.trace.api.ServiceHash;
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

  // Injected fields. When adding a new one, be sure to update all the methods below.
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

  private static final int INITIAL_CAPACITY = computeInitialCapacity();

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
    boolean appendComment = preferAppend;
    if (sql == null || sql.isEmpty()) {
      return sql;
    }
    if (hasDDComment(sql, appendComment)) {
      return sql;
    }

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
      if (dbType.startsWith("postgres") && sql.indexOf("/*+") > 0) {
        appendComment = true;
      }
    }

    AgentSpan currSpan = activeSpan();
    Object peerServiceObj = null;
    if (currSpan != null) {
      peerServiceObj = currSpan.getTag(Tags.PEER_SERVICE);
    }

    final Config config = Config.get();
    final String parentService = config.getServiceName();
    final String env = config.getEnv();
    final String version = config.getVersion();
    String containerTagsHash = ContainerInfo.get().getContainerTagsHash();
    final String serviceHash =
        Long.toString(ServiceHash.getBaseHash(parentService, env, containerTagsHash));
    //        config.isDbDbmInjectServiceHash() ; // TODO && baseHash != null
    String peerService = peerServiceObj != null ? peerServiceObj.toString() : null;

    final int commentSize =
        capacity(
            parentService,
            dbService,
            hostname,
            dbName,
            peerService,
            env,
            version,
            traceParent,
            serviceHash);
    StringBuilder sb = new StringBuilder(sql.length() + commentSize);

    if (appendComment) {
      sb.append(sql);
      sb.append(SPACE);
    }
    sb.append(OPEN_COMMENT);
    int initSize = sb.length();
    append(sb, PARENT_SERVICE, parentService, initSize);
    append(sb, DATABASE_SERVICE, dbService, initSize);
    append(sb, DD_HOSTNAME, hostname, initSize);
    append(sb, DD_DB_NAME, dbName, initSize);
    append(sb, DD_PEER_SERVICE, peerService, initSize);
    append(sb, DD_ENV, env, initSize);
    append(sb, DD_VERSION, version, initSize);
    append(sb, TRACEPARENT, traceParent, initSize);
    // TODO only if DB_DBM_INJECT_SERVICE_HASH_ENABLED
    //    append(sb, DD_SERVICE_HASH, serviceHash, initSize);
    if (initSize == sb.length()) {
      // no comment was added
      // TODO Is it even possible for all the fields to be unset?
      return sql;
    }
    sb.append(CLOSE_COMMENT);
    if (!appendComment) {
      sb.append(SPACE);
      sb.append(sql);
    }
    return sb.toString();
  }

  private static boolean hasDDComment(String sql, final boolean appendComment) {
    // first check to see if sql ends with a comment
    if ((!(sql.endsWith(CLOSE_COMMENT)) && appendComment)
        || ((!(sql.startsWith(OPEN_COMMENT))) && !appendComment)) {
      return false;
    }
    // else check to see if it's a DBM trace sql comment
    int startIdx = OPEN_COMMENT_LEN;
    if (appendComment) {
      startIdx = sql.lastIndexOf(OPEN_COMMENT) + OPEN_COMMENT_LEN;
    }
    int startComment = appendComment ? startIdx : sql.length();
    // TODO isn't the parent service always exits? if so there is no need to do other checks here
    return startComment > OPEN_COMMENT_LEN
        && (hasMatchingSubstring(sql, startIdx, PARENT_SERVICE)
            || hasMatchingSubstring(sql, startIdx, DATABASE_SERVICE)
            || hasMatchingSubstring(sql, startIdx, DD_HOSTNAME)
            || hasMatchingSubstring(sql, startIdx, DD_DB_NAME)
            || hasMatchingSubstring(sql, startIdx, DD_PEER_SERVICE)
            || hasMatchingSubstring(sql, startIdx, DD_ENV)
            || hasMatchingSubstring(sql, startIdx, DD_VERSION)
            || hasMatchingSubstring(sql, startIdx, TRACEPARENT)
            || hasMatchingSubstring(sql, startIdx, DD_SERVICE_HASH));
  }

  private static boolean hasMatchingSubstring(String sql, int startIndex, String substring) {
    final boolean tooLong = startIndex + substring.length() >= sql.length();
    if (tooLong || !(sql.charAt(startIndex + substring.length()) == EQUALS)) {
      return false;
    }
    return sql.startsWith(substring, startIndex);
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

  private static int capacity(
      final String parentService,
      final String dbService,
      String hostname,
      String dbName,
      String peerService,
      final String env,
      final String version,
      final String traceparent,
      String serviceHash) {
    int len = INITIAL_CAPACITY;
    if (null != parentService) {
      len += parentService.length();
    }
    if (null != dbService) {
      len += dbService.length();
    }
    if (null != hostname) {
      len += hostname.length();
    }
    if (null != dbName) {
      len += dbName.length();
    }
    if (null != peerService) {
      len += peerService.length();
    }
    if (null != env) {
      len += env.length();
    }
    if (null != version) {
      len += version.length();
    }
    if (null != traceparent) {
      len += traceparent.length();
    }
    if (null != serviceHash) {
      len += serviceHash.length();
    }
    return len;
  }

  private static int computeInitialCapacity() {
    int tagKeysLen =
        PARENT_SERVICE.length()
            + DATABASE_SERVICE.length()
            + DD_HOSTNAME.length()
            + DD_DB_NAME.length()
            + DD_PEER_SERVICE.length()
            + DD_ENV.length()
            + DD_VERSION.length()
            + TRACEPARENT.length()
            + DD_SERVICE_HASH.length();
    int extraCharsLen =
        4 * NUMBER_OF_FIELDS // two quotes, one equals & one comma * number of fields
            + OPEN_COMMENT.length()
            + CLOSE_COMMENT.length();
    return tagKeysLen + extraCharsLen;
  }
}
