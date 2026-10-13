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
    String service = encode(dbService);
    String host = encode(hostname);
    String database = encode(dbName);
    String peerService = encode(getPeerService());
    String parent = encode(traceParent);
    Config config = Config.get();
    String serviceHash =
        config.isDbmInjectSqlBaseHash() && config.isExperimentalPropagateProcessTagsEnabled()
            ? encode(BaseHash.getBaseHashStr())
            : null;
    long capacity =
        staticPrefix.length()
            + fieldSize(DATABASE_SERVICE, service)
            + fieldSize(DD_HOSTNAME, host)
            + fieldSize(DD_DB_NAME, database)
            + fieldSize(DD_PEER_SERVICE, peerService)
            + fieldSize(TRACEPARENT, parent)
            + fieldSize(DD_SERVICE_HASH, serviceHash);
    StringBuilder sb =
        new StringBuilder((int) Math.min(capacity, Integer.MAX_VALUE)).append(staticPrefix);
    appendEncoded(sb, DATABASE_SERVICE, service);
    appendEncoded(sb, DD_HOSTNAME, host);
    appendEncoded(sb, DD_DB_NAME, database);
    appendEncoded(sb, DD_PEER_SERVICE, peerService);
    appendEncoded(sb, TRACEPARENT, parent);
    appendEncoded(sb, DD_SERVICE_HASH, serviceHash);
    return sb.length() > 0 ? sb.toString() : null;
  }

  private static void ensureStaticPrefixComputed() {
    if (staticPrefixComputed) {
      return;
    }
    Config config = Config.get();
    final StringBuilder sb = new StringBuilder(512); // big enough not to be resized

    appendEncoded(sb, PARENT_SERVICE, encode(config.getServiceName()));
    appendEncoded(sb, DD_ENV, encode(config.getEnv()));
    appendEncoded(sb, DD_VERSION, encode(config.getVersion()));
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
    if (val == null || val.isEmpty()) {
      return null;
    }
    try {
      return URLEncoder.encode(val, UTF8);
    } catch (UnsupportedEncodingException exe) {
      if (log.isDebugEnabled()) {
        log.debug("exception thrown while encoding comment key {}", val, exe);
      }
    }
    return val;
  }

  /** Includes a possible separator, equals sign and quotes for a nonempty encoded field. */
  private static long fieldSize(String key, String value) {
    return value == null ? 0 : (long) key.length() + value.length() + 4;
  }

  private static void appendEncoded(StringBuilder sb, String key, String value) {
    if (value == null) {
      return;
    }
    if (sb.length() > 0) {
      sb.append(COMMA);
    }
    sb.append(key).append(EQUALS).append(QUOTE).append(value).append(QUOTE);
  }
}
