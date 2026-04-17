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
   * Cached static suffix: "dde='&lt;env&gt;',ddpv='&lt;version&gt;'" Computed lazily on first use
   * since Config may not be available at class-load time.
   */
  private static volatile String cachedStaticSuffix;

  /** Cached URL-encoded service name from Config. */
  private static volatile String cachedEncodedServiceName;

  /** The Config instance used to compute the cached values, for staleness detection. */
  private static volatile Config cachedConfigInstance;

  /**
   * Cached per-connection prefix fragments keyed by (dbService, hostname, dbName). Each entry holds
   * the pre-encoded "ddps='...',dddbs='...',ddh='...',dddb='...'" fragment, combining the static
   * service name with per-connection values to preserve the correct field ordering.
   */
  private static final int MAX_CONNECTION_CACHE_SIZE = 256;

  private static final ConcurrentHashMap<ConnectionKey, String> connectionPrefixCache =
      new ConcurrentHashMap<>();

  // Used by SQLCommenter and MongoCommentInjector to avoid duplicate comment injection
  public static boolean containsTraceComment(String commentContent) {
    return commentContent.contains(PARENT_SERVICE + "=")
        || commentContent.contains(DATABASE_SERVICE + "=")
        || commentContent.contains(DD_HOSTNAME + "=")
        || commentContent.contains(DD_DB_NAME + "=")
        || commentContent.contains(DD_PEER_SERVICE + "=")
        || commentContent.contains(DD_ENV + "=")
        || commentContent.contains(DD_VERSION + "=")
        || commentContent.contains(TRACEPARENT + "=")
        || commentContent.contains(DD_SERVICE_HASH + "=");
  }

  // Build database comment content without comment delimiters such as /* */
  public static String buildComment(
      String dbService, String dbType, String hostname, String dbName, String traceParent) {

    Config config = Config.get();

    // Ensure static values are initialized
    ensureStaticValuesInitialized(config);

    // Get or compute the prefix: ddps='...',dddbs='...',ddh='...',dddb='...'
    String prefix = getConnectionPrefix(dbService, hostname, dbName);

    // Get the cached static suffix: dde='...',ddpv='...'
    String suffix = cachedStaticSuffix;

    // Dynamic values that change per-call
    String peerService = getPeerService();
    boolean injectBaseHash =
        config.isDbmInjectSqlBaseHash() && config.isExperimentalPropagateProcessTagsEnabled();
    String baseHash = injectBaseHash ? BaseHash.getBaseHashStr() : null;

    // Estimate capacity
    int capacity =
        (prefix != null ? prefix.length() : 0)
            + (suffix != null ? suffix.length() : 0)
            + 128; // generous estimate for dynamic parts
    StringBuilder sb = new StringBuilder(capacity);

    // Append prefix: ddps, dddbs, ddh, dddb (preserves original field order)
    if (prefix != null) {
      sb.append(prefix);
    }

    // Append dynamic: ddprs
    appendEncoded(sb, DD_PEER_SERVICE, peerService);

    // Append cached static suffix: dde, ddpv
    if (suffix != null) {
      if (sb.length() > 0) {
        sb.append(COMMA);
      }
      sb.append(suffix);
    }

    // Append dynamic: traceparent
    appendEncoded(sb, TRACEPARENT, traceParent);

    // Append conditional: ddsh
    if (injectBaseHash) {
      appendEncoded(sb, DD_SERVICE_HASH, baseHash);
    }

    return sb.length() > 0 ? sb.toString() : null;
  }

  /**
   * Initializes the cached static values (service name, env, version) from Config. These never
   * change for the agent's lifetime. Identity-checks the Config instance to handle test scenarios
   * where Config is rebuilt.
   */
  private static void ensureStaticValuesInitialized(Config config) {
    if (cachedConfigInstance == config) {
      return;
    }
    // Config instance changed (or first call) -- recompute all cached values
    // URL-encode the service name
    String serviceName = config.getServiceName();
    cachedEncodedServiceName = encodeValue(serviceName);

    // Build the static suffix: dde='...', ddpv='...'
    StringBuilder sb = new StringBuilder(48);
    appendEncoded(sb, DD_ENV, config.getEnv());
    appendEncoded(sb, DD_VERSION, config.getVersion());
    cachedStaticSuffix = sb.length() > 0 ? sb.toString() : null;

    // Clear connection prefix cache since it includes the service name
    connectionPrefixCache.clear();

    // Publish the config instance last so other threads see all updates
    cachedConfigInstance = config;
  }

  /**
   * Returns a cached prefix fragment combining ddps (static service name) with dddbs, ddh, and dddb
   * (per-connection values). This preserves the original field ordering:
   * ddps='...',dddbs='...',ddh='...',dddb='...'
   */
  private static String getConnectionPrefix(String dbService, String hostname, String dbName) {
    ConnectionKey key = new ConnectionKey(dbService, hostname, dbName);
    String fragment = connectionPrefixCache.get(key);
    if (fragment != null) {
      return fragment;
    }
    // Build the prefix fragment preserving original order
    StringBuilder sb = new StringBuilder(96);
    appendPreEncoded(sb, PARENT_SERVICE, cachedEncodedServiceName);
    appendEncoded(sb, DATABASE_SERVICE, dbService);
    appendEncoded(sb, DD_HOSTNAME, hostname);
    appendEncoded(sb, DD_DB_NAME, dbName);
    fragment = sb.length() > 0 ? sb.toString() : null;
    if (fragment != null) {
      // Evict all entries if cache grows too large to bound memory
      if (connectionPrefixCache.size() >= MAX_CONNECTION_CACHE_SIZE) {
        connectionPrefixCache.clear();
      }
      connectionPrefixCache.put(key, fragment);
    }
    return fragment;
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

  /** URL-encodes a value, returning null if the input is null or empty. */
  private static String encodeValue(String value) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    try {
      return URLEncoder.encode(value, UTF8);
    } catch (UnsupportedEncodingException e) {
      return value;
    }
  }

  /**
   * Appends a key='already-encoded-value' pair to the StringBuilder. Used for values that have
   * already been URL-encoded and cached.
   */
  private static void appendPreEncoded(StringBuilder sb, String key, String encodedValue) {
    if (null == encodedValue || encodedValue.isEmpty()) {
      return;
    }
    if (sb.length() > 0) {
      sb.append(COMMA);
    }
    sb.append(key).append(EQUALS).append(QUOTE).append(encodedValue).append(QUOTE);
  }

  /**
   * Appends an encoded key='value' pair to the StringBuilder if value is non-null and non-empty.
   */
  private static void appendEncoded(StringBuilder sb, String key, String value) {
    if (null == value || value.isEmpty()) {
      return;
    }
    String encodedValue;
    try {
      encodedValue = URLEncoder.encode(value, UTF8);
    } catch (UnsupportedEncodingException e) {
      encodedValue = value;
    }
    if (sb.length() > 0) {
      sb.append(COMMA);
    }
    sb.append(key).append(EQUALS).append(QUOTE).append(encodedValue).append(QUOTE);
  }

  /** Resets cached state. Visible for testing. */
  static void resetCache() {
    cachedConfigInstance = null;
    cachedStaticSuffix = null;
    cachedEncodedServiceName = null;
    connectionPrefixCache.clear();
  }

  /**
   * Composite key for per-connection prefix cache. Holds the raw (unencoded) dbService, hostname,
   * and dbName values.
   */
  private static final class ConnectionKey {
    private final String dbService;
    private final String hostname;
    private final String dbName;
    private final int hashCode;

    ConnectionKey(String dbService, String hostname, String dbName) {
      this.dbService = dbService;
      this.hostname = hostname;
      this.dbName = dbName;
      // Pre-compute hash code since this is an immutable key
      int h = 17;
      h = 31 * h + (dbService != null ? dbService.hashCode() : 0);
      h = 31 * h + (hostname != null ? hostname.hashCode() : 0);
      h = 31 * h + (dbName != null ? dbName.hashCode() : 0);
      this.hashCode = h;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof ConnectionKey)) return false;
      ConnectionKey that = (ConnectionKey) o;
      return equals(dbService, that.dbService)
          && equals(hostname, that.hostname)
          && equals(dbName, that.dbName);
    }

    private static boolean equals(String a, String b) {
      return a == null ? b == null : a.equals(b);
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }
}
