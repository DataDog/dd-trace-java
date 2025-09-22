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

/** Shared database comment builder for generating trace context comments for SQL DBs and MongoDB */
public class SharedDBCommenter {
  private static final Logger log = LoggerFactory.getLogger(SharedDBCommenter.class);
  private static final String UTF8 = StandardCharsets.UTF_8.toString();

  private static final char EQUALS = '=';
  private static final char COMMA = ',';
  private static final char QUOTE = '\'';

  // Injected fields. When adding a new one, be sure to update this and the methods below.
  private static final String PARENT_SERVICE = encode("ddps");
  private static final String DATABASE_SERVICE = encode("dddbs");
  private static final String DD_HOSTNAME = encode("ddh");
  private static final String DD_DB_NAME = encode("dddb");
  private static final String DD_PEER_SERVICE = "ddprs";
  private static final String DD_ENV = encode("dde");
  private static final String DD_VERSION = encode("ddpv");
  private static final String TRACEPARENT = encode("traceparent");
  private static final String DD_SERVICE_HASH = encode("ddsh");

  // Used by SQLCommenter and MongoCommentInjector to avoid duplicate comment injection
  public static boolean containsTraceComment(String commentContent) {
    return commentContent.contains(PARENT_SERVICE + "=")
        || commentContent.contains(DATABASE_SERVICE + "=")
        || commentContent.contains(DD_ENV + "=")
        || commentContent.contains(TRACEPARENT + "=");
  }

  // Build database comment content without comment delimiters such as /* */
  public static String buildComment(
      String dbService, String dbType, String hostname, String dbName, String traceParent) {

    Config config = Config.get();
    StringBuilder sb = new StringBuilder();

    int initSize = 0; // No initial content for pure comment
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

    return sb.length() > 0 ? sb.toString() : null;
  }

  private static String getPeerService() {
    AgentSpan span = activeSpan();
    Object peerService = null;
    if (span != null) {
      peerService = span.getTag(Tags.PEER_SERVICE);
    }
    return peerService != null ? peerService.toString() : null;
  }

  private static String encode(String val) {
    try {
      return URLEncoder.encode(val, UTF8);
    } catch (UnsupportedEncodingException exe) {
      if (log.isDebugEnabled()) {
        log.debug("exception thrown while encoding comment key {}", val, exe);
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
      encodedValue = value;
    }
    if (sb.length() > initSize) {
      sb.append(COMMA);
    }
    sb.append(key).append(EQUALS).append(QUOTE).append(encodedValue).append(QUOTE);
  }
}
