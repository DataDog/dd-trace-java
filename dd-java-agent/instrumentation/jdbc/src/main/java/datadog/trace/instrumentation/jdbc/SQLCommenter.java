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
  private static final String PARENT_SERVICE = encode("ddps");
  private static final String DATABASE_SERVICE = encode("dddbs");
  private static final String DD_HOSTNAME = encode("ddh");
  private static final String DD_DB_NAME = encode("dddb");
  private static final String DD_PEER_SERVICE = "ddprs";
  private static final String DD_ENV = encode("dde");
  private static final String DD_VERSION = encode("ddpv");
  private static final String DD_SERVICE_HASH = encode("ddsh");
  private static final String TRACEPARENT = encode("traceparent");
  private static final char EQUALS = '=';
  private static final char COMMA = ',';
  private static final char QUOTE = '\'';
  private static final char SPACE = ' ';
  private static final String OPEN_COMMENT = "/*";
  private static final String CLOSE_COMMENT = "*/";
  private static final int INITIAL_CAPACITY = computeInitialCapacity();

  public static String append(
      final String sql,
      final String dbService,
      final String dbType,
      final String hostname,
      final String dbName) {
    return inject(sql, dbService, dbType, hostname, dbName, null, false, true);
  }

  public static String prepend(
      final String sql,
      final String dbService,
      final String dbType,
      final String hostname,
      final String dbName) {
    return inject(sql, dbService, dbType, hostname, dbName, null, false, false);
  }

  public static String getFirstWord(String sql) {
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
      final String sql,
      final String dbService,
      final String dbType,
      final String hostname,
      final String dbName,
      final String traceParent,
      final boolean injectTrace,
      boolean appendComment) {
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
      // procedure
      // invocation but they seem ok with it after so we force append mode
      if (firstWord.equalsIgnoreCase("call")) {
        appendComment = true;
      }

      // Append the comment in the case of a pg_hint_plan extension
      if (dbType.startsWith("postgres") && containsPgHint(sql)) {
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
    final int commentSize = capacity(traceParent, parentService, dbService, env, version);
    StringBuilder sb = new StringBuilder(sql.length() + commentSize);
    boolean commentAdded = false;
    String peerService = peerServiceObj != null ? peerServiceObj.toString() : null;

    if (appendComment) {
      sb.append(sql);
      sb.append(SPACE);
      sb.append(OPEN_COMMENT);
      commentAdded =
          toComment(
              sb,
              injectTrace,
              parentService,
              dbService,
              hostname,
              dbName,
              peerService,
              env,
              version,
              traceParent,
              serviceHash);
      sb.append(CLOSE_COMMENT);
    } else {
      sb.append(OPEN_COMMENT);
      commentAdded =
          toComment(
              sb,
              injectTrace,
              parentService,
              dbService,
              hostname,
              dbName,
              peerService,
              env,
              version,
              traceParent,
              serviceHash);

      sb.append(CLOSE_COMMENT);
      sb.append(SPACE);
      sb.append(sql);
    }
    if (!commentAdded) {
      return sql;
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
    int startIdx = 2;
    if (appendComment) {
      startIdx = sql.lastIndexOf(OPEN_COMMENT) + 2;
    }
    int startComment = appendComment ? startIdx : sql.length();
    boolean found = false;
    if (startComment > 2) {
      if (hasMatchingSubstring(sql, startIdx, PARENT_SERVICE)) {
        found = true;
      } else if (hasMatchingSubstring(sql, startIdx, DATABASE_SERVICE)) {
        found = true;
      } else if (hasMatchingSubstring(sql, startIdx, DD_HOSTNAME)) {
        found = true;
      } else if (hasMatchingSubstring(sql, startIdx, DD_DB_NAME)) {
        found = true;
      } else if (hasMatchingSubstring(sql, startIdx, DD_ENV)) {
        found = true;
      } else if (hasMatchingSubstring(sql, startIdx, DD_VERSION)) {
        found = true;
      } else if (hasMatchingSubstring(sql, startIdx, TRACEPARENT)) {
        found = true;
      }
    }
    return found;
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

  protected static boolean toComment(
      StringBuilder sb,
      final boolean injectTrace,
      final String parentService,
      final String dbService,
      final String hostname,
      final String dbName,
      final String peerService,
      final String env,
      final String version,
      final String traceparent,
      final String serviceHash) {
    int emptySize = sb.length();
    append(sb, PARENT_SERVICE, parentService, false);
    append(sb, DATABASE_SERVICE, dbService, sb.length() > emptySize);
    append(sb, DD_HOSTNAME, hostname, sb.length() > emptySize);
    append(sb, DD_DB_NAME, dbName, sb.length() > emptySize);
    if (peerService != null) {
      append(sb, DD_PEER_SERVICE, peerService, sb.length() > emptySize);
    }
    append(sb, DD_ENV, env, sb.length() > emptySize);
    append(sb, DD_VERSION, version, sb.length() > emptySize);
    if (injectTrace) {
      append(sb, TRACEPARENT, traceparent, sb.length() > emptySize);
    }
    // TODO only if DB_DBM_INJECT_SERVICE_HASH_ENABLED
    append(sb, DD_SERVICE_HASH, serviceHash, sb.length() > emptySize);
    return sb.length() > emptySize;
  }

  private static void append(StringBuilder sb, String key, String value, boolean prependComma) {
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
            + DD_HOSTNAME.length()
            + DD_DB_NAME.length()
            + DD_ENV.length()
            + DD_VERSION.length()
            + TRACEPARENT.length();
    int extraCharsLen =
        4 * 5
            + OPEN_COMMENT.length()
            + CLOSE_COMMENT.length(); // two quotes, one equals & one comma * 5 + \* */
    return tagKeysLen + extraCharsLen;
  }

  // pg_hint_plan extension works by checking the first block comment
  // we'll have to append the traced comment if there is a pghint
  private static boolean containsPgHint(String sql) {
    return sql.indexOf("/*+") > 0;
  }
}
