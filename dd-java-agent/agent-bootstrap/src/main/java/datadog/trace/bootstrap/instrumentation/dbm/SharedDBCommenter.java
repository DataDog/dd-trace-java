package datadog.trace.bootstrap.instrumentation.dbm;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.BaseHash;
import datadog.trace.api.Config;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.util.SubSequence;
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

  // Pre-built "<key>=" needles for containsTraceComment, computed once at class init. The keys
  // are assigned via encode(...), so "KEY + =" is a runtime concat, not a compile-time constant;
  // doing it per call allocated nine throwaway Strings on every non-matching check.
  private static final String PARENT_SERVICE_EQ = PARENT_SERVICE + "=";
  private static final String DATABASE_SERVICE_EQ = DATABASE_SERVICE + "=";
  private static final String DD_HOSTNAME_EQ = DD_HOSTNAME + "=";
  private static final String DD_DB_NAME_EQ = DD_DB_NAME + "=";
  private static final String DD_PEER_SERVICE_EQ = DD_PEER_SERVICE + "=";
  private static final String DD_ENV_EQ = DD_ENV + "=";
  private static final String DD_VERSION_EQ = DD_VERSION + "=";
  private static final String TRACEPARENT_EQ = TRACEPARENT + "=";
  private static final String DD_SERVICE_HASH_EQ = DD_SERVICE_HASH + "=";

  // Pre-encoded "key='encoded_value'" fragments for the invariant fields (the values
  // come from Config are effectively immutable post-init in production).
  // Note about the visibility: needs to be visible but can tolerate races (reason why it's not
  // atomic)
  private static volatile boolean staticPrefixComputed = false;
  private static volatile String staticPrefix;

  // Used by SQLCommenter and MongoCommentInjector to avoid duplicate comment injection. Mongo
  // passes the already-extracted comment body; SQLCommenter uses the range overload to check it
  // in place. Both run the same nine "<key>=" needle checks.
  public static boolean containsTraceComment(String commentContent) {
    return containsTraceComment(commentContent, 0, commentContent.length());
  }

  /**
   * Range overload: true if {@code sql} contains a trace-comment needle fully within {@code [from,
   * to)} -- checks the comment body in place, with no substring allocation of the region.
   */
  public static boolean containsTraceComment(String sql, int from, int to) {
    // Zero-copy view of the comment body; reads like ordinary String.contains, no substring.
    SubSequence comment = SubSequence.of(sql, from, to);
    return comment.contains(PARENT_SERVICE_EQ)
        || comment.contains(DATABASE_SERVICE_EQ)
        || comment.contains(DD_HOSTNAME_EQ)
        || comment.contains(DD_DB_NAME_EQ)
        || comment.contains(DD_PEER_SERVICE_EQ)
        || comment.contains(DD_ENV_EQ)
        || comment.contains(DD_VERSION_EQ)
        || comment.contains(TRACEPARENT_EQ)
        || comment.contains(DD_SERVICE_HASH_EQ);
  }

  // Build database comment content without comment delimiters such as /* */
  public static String buildComment(
      String dbService, String dbType, String hostname, String dbName, String traceParent) {
    ensureStaticPrefixComputed();

    // we can calculate the precise size - having a rough estimation is perhaps faster
    StringBuilder sb = new StringBuilder(1024).append(staticPrefix);
    int initSize = 0; // No initial content for pure comment
    append(sb, DATABASE_SERVICE, dbService, initSize);
    append(sb, DD_HOSTNAME, hostname, initSize);
    append(sb, DD_DB_NAME, dbName, initSize);
    append(sb, DD_PEER_SERVICE, getPeerService(), initSize);
    append(sb, TRACEPARENT, traceParent, initSize);
    final Config config = Config.get();
    if (config.isDbmInjectSqlBaseHash() && config.isExperimentalPropagateProcessTagsEnabled()) {
      append(sb, DD_SERVICE_HASH, BaseHash.getBaseHashStr(), initSize);
    }

    return sb.length() > 0 ? sb.toString() : null;
  }

  private static void ensureStaticPrefixComputed() {
    if (staticPrefixComputed) {
      return;
    }
    Config config = Config.get();
    final StringBuilder sb = new StringBuilder(512); // big enough not to be resized

    append(sb, PARENT_SERVICE, config.getServiceName(), 0);
    append(sb, DD_ENV, config.getEnv(), 0);
    append(sb, DD_VERSION, config.getVersion(), 0);
    staticPrefix = sb.toString();
    staticPrefixComputed = true;
  }

  @VisibleForTesting
  public static void resetStaticPrefixForTesting() {
    staticPrefixComputed = false;
  }

  private static String getPeerService() {
    AgentSpan span = activeSpan();
    Object peerService = null;
    if (span != null) {
      // FIXME: this will never work since peer service is computed later if enabled
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
