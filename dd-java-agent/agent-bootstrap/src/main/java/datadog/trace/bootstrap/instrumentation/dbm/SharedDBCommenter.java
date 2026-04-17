package datadog.trace.bootstrap.instrumentation.dbm;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.BaseHash;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
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

  /**
   * Cache for static comment strings (those without traceParent or peerService). The key combines
   * dbService, hostname, dbName, and Config identity to ensure correctness if Config is replaced.
   */
  private static final ConcurrentHashMap<StaticCommentKey, String> staticCommentCache =
      new ConcurrentHashMap<>();

  // Pre-computed marker strings for trace comment detection
  private static final String PARENT_SERVICE_EQ = PARENT_SERVICE + "=";
  private static final String DATABASE_SERVICE_EQ = DATABASE_SERVICE + "=";
  private static final String DD_HOSTNAME_EQ = DD_HOSTNAME + "=";
  private static final String DD_DB_NAME_EQ = DD_DB_NAME + "=";
  private static final String DD_PEER_SERVICE_EQ = DD_PEER_SERVICE + "=";
  private static final String DD_ENV_EQ = DD_ENV + "=";
  private static final String DD_VERSION_EQ = DD_VERSION + "=";
  private static final String TRACEPARENT_EQ = TRACEPARENT + "=";
  private static final String DD_SERVICE_HASH_EQ = DD_SERVICE_HASH + "=";

  // Used by SQLCommenter and MongoCommentInjector to avoid duplicate comment injection
  public static boolean containsTraceComment(String commentContent) {
    return commentContent.contains(PARENT_SERVICE_EQ)
        || commentContent.contains(DATABASE_SERVICE_EQ)
        || commentContent.contains(DD_HOSTNAME_EQ)
        || commentContent.contains(DD_DB_NAME_EQ)
        || commentContent.contains(DD_PEER_SERVICE_EQ)
        || commentContent.contains(DD_ENV_EQ)
        || commentContent.contains(DD_VERSION_EQ)
        || commentContent.contains(TRACEPARENT_EQ)
        || commentContent.contains(DD_SERVICE_HASH_EQ);
  }

  /**
   * Checks for trace comment markers within a range of the given string, without allocating a
   * substring. Searches within [fromIndex, toIndex) of the source string.
   */
  public static boolean containsTraceComment(String sql, int fromIndex, int toIndex) {
    return containsInRange(sql, PARENT_SERVICE_EQ, fromIndex, toIndex)
        || containsInRange(sql, DATABASE_SERVICE_EQ, fromIndex, toIndex)
        || containsInRange(sql, DD_HOSTNAME_EQ, fromIndex, toIndex)
        || containsInRange(sql, DD_DB_NAME_EQ, fromIndex, toIndex)
        || containsInRange(sql, DD_PEER_SERVICE_EQ, fromIndex, toIndex)
        || containsInRange(sql, DD_ENV_EQ, fromIndex, toIndex)
        || containsInRange(sql, DD_VERSION_EQ, fromIndex, toIndex)
        || containsInRange(sql, TRACEPARENT_EQ, fromIndex, toIndex)
        || containsInRange(sql, DD_SERVICE_HASH_EQ, fromIndex, toIndex);
  }

  /** Checks if {@code target} appears within the range [fromIndex, toIndex) of {@code source}. */
  private static boolean containsInRange(String source, String target, int fromIndex, int toIndex) {
    int targetLen = target.length();
    int limit = toIndex - targetLen;
    for (int i = fromIndex; i <= limit; i++) {
      if (source.regionMatches(i, target, 0, targetLen)) {
        return true;
      }
    }
    return false;
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

  /**
   * Builds the static portion of a database comment that does not change per-span. This includes
   * parentService, databaseService, hostname, dbName, env, version, and serviceHash. The dynamic
   * parts (peerService, traceParent) are excluded and must be appended separately.
   *
   * <p>Results are cached per (dbService, hostname, dbName, Config) combination to avoid redundant
   * URLEncoder.encode() calls and StringBuilder work on every query execution.
   *
   * @return the static comment prefix, or null if no static fields are set
   */
  public static String buildStaticComment(String dbService, String hostname, String dbName) {
    Config config = Config.get();
    StaticCommentKey key = new StaticCommentKey(dbService, hostname, dbName, config);
    String cached = staticCommentCache.get(key);
    if (cached != null) {
      return cached;
    }

    StringBuilder sb = new StringBuilder();

    int initSize = 0;
    append(sb, PARENT_SERVICE, config.getServiceName(), initSize);
    append(sb, DATABASE_SERVICE, dbService, initSize);
    append(sb, DD_HOSTNAME, hostname, initSize);
    append(sb, DD_DB_NAME, dbName, initSize);
    // peerService is per-span, skip here
    append(sb, DD_ENV, config.getEnv(), initSize);
    append(sb, DD_VERSION, config.getVersion(), initSize);
    // traceParent is per-span, skip here

    if (config.isDbmInjectSqlBaseHash() && config.isExperimentalPropagateProcessTagsEnabled()) {
      append(sb, DD_SERVICE_HASH, BaseHash.getBaseHashStr(), initSize);
    }

    String result = sb.length() > 0 ? sb.toString() : null;
    if (result != null) {
      staticCommentCache.putIfAbsent(key, result);
    }
    return result;
  }

  /** Returns true if the current active span has a non-null, non-empty peer service tag. */
  public static boolean hasPeerService() {
    AgentSpan span = activeSpan();
    if (span != null) {
      Object peerService = span.getTag(Tags.PEER_SERVICE);
      if (peerService != null) {
        String str = peerService.toString();
        return str != null && !str.isEmpty();
      }
    }
    return false;
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

  /**
   * Cache key for static comment lookup. Uses Config identity (rather than individual field values)
   * to ensure the cache is automatically invalidated if Config is replaced (as happens in tests).
   * In production, Config is created once, so identity comparison is both correct and cheap.
   */
  private static final class StaticCommentKey {
    private final String dbService;
    private final String hostname;
    private final String dbName;
    private final Config config;
    private final int hashCode;

    StaticCommentKey(String dbService, String hostname, String dbName, Config config) {
      this.dbService = dbService;
      this.hostname = hostname;
      this.dbName = dbName;
      this.config = config;
      int h = 17;
      h = 31 * h + (dbService != null ? dbService.hashCode() : 0);
      h = 31 * h + (hostname != null ? hostname.hashCode() : 0);
      h = 31 * h + (dbName != null ? dbName.hashCode() : 0);
      h = 31 * h + System.identityHashCode(config);
      this.hashCode = h;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof StaticCommentKey)) return false;
      StaticCommentKey that = (StaticCommentKey) o;
      return config == that.config
          && eq(dbService, that.dbService)
          && eq(hostname, that.hostname)
          && eq(dbName, that.dbName);
    }

    private static boolean eq(String a, String b) {
      return a == null ? b == null : a.equals(b);
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }
}
