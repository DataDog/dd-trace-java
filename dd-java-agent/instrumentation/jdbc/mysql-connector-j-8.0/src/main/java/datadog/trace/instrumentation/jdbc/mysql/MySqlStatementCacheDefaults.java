package datadog.trace.instrumentation.jdbc.mysql;

import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Decides which connection properties to hand MySQL Connector/J so that its client-side prepared
 * statement cache is on, using the settings HikariCP recommends.
 *
 * <p>Connector/J defaults to {@code cachePrepStmts=false}, so every {@code prepareStatement} parses
 * the SQL again on the client. With the cache on, the parse result is kept per connection and keyed
 * by SQL text. Statements are still client-side ({@code useServerPrepStmts} is never touched), so
 * what is sent to the server does not change.
 *
 * <p>The caller's {@link Properties} are never modified. Nothing is changed when the connection
 * already says anything about the cache, or uses a setting that could make the outcome harder to
 * predict:
 *
 * <ul>
 *   <li>{@code cachePrepStmts} set anywhere, to any value (an explicit {@code false} wins)
 *   <li>{@code useServerPrepStmts} set anywhere: with server-side statements the cache also pools
 *       statement handles on the server, which is a behavior change
 *   <li>{@code useConfigs} or {@code propertiesTransform}: they can set the cache themselves
 * </ul>
 *
 * <p>{@code prepStmtCacheSize} and {@code prepStmtCacheSqlLimit} are only added when absent, so an
 * explicit size in the URL is not overridden (Connector/J lets {@link Properties} win over the
 * URL).
 *
 * <p>Property names are matched case-insensitively, like Connector/J does. The URL check is a
 * substring search on the whole URL, which also covers per-host properties ({@code
 * (host=a,cachePrepStmts=false)}). A false positive only means the URL is left alone.
 */
public final class MySqlStatementCacheDefaults {

  public static final String CACHE_PREP_STMTS = "cachePrepStmts";
  public static final String PREP_STMT_CACHE_SIZE = "prepStmtCacheSize";
  public static final String PREP_STMT_CACHE_SQL_LIMIT = "prepStmtCacheSqlLimit";

  public static final String DEFAULT_CACHE_SIZE = "250";
  public static final String DEFAULT_CACHE_SQL_LIMIT = "2048";

  private static final String[] OPT_OUT_KEYS = {
    "cachePrepStmts", "useServerPrepStmts", "useConfigs", "propertiesTransform"
  };

  private MySqlStatementCacheDefaults() {}

  /**
   * Returns the properties to connect with: {@code info} itself when nothing should change, or a
   * copy of it with the cache settings added.
   */
  public static Properties withDefaults(final String url, final Properties info) {
    if (!isClassicMySqlUrl(url)) {
      return info;
    }
    final String lowerUrl = url.toLowerCase(Locale.ROOT);
    for (final String key : OPT_OUT_KEYS) {
      if (isSet(lowerUrl, info, key)) {
        return info;
      }
    }
    final Properties copy = copyOf(info);
    copy.setProperty(CACHE_PREP_STMTS, "true");
    if (!isSet(lowerUrl, info, PREP_STMT_CACHE_SIZE)) {
      copy.setProperty(PREP_STMT_CACHE_SIZE, DEFAULT_CACHE_SIZE);
    }
    if (!isSet(lowerUrl, info, PREP_STMT_CACHE_SQL_LIMIT)) {
      copy.setProperty(PREP_STMT_CACHE_SQL_LIMIT, DEFAULT_CACHE_SQL_LIMIT);
    }
    return copy;
  }

  /**
   * {@code jdbc:mysql:} (single host, failover, {@code loadbalance:}, {@code replication:}) and the
   * DNS SRV variants. Not the X DevAPI ({@code mysqlx:}), which has no such cache.
   */
  static boolean isClassicMySqlUrl(final String url) {
    return url != null && (url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mysql+srv:"));
  }

  private static boolean isSet(final String lowerUrl, final Properties info, final String key) {
    if (lowerUrl.contains(key.toLowerCase(Locale.ROOT))) {
      return true;
    }
    if (info == null) {
      return false;
    }
    // stringPropertyNames() includes the defaults chain; keySet() also has non-String keys/values
    for (final String name : info.stringPropertyNames()) {
      if (key.equalsIgnoreCase(name)) {
        return true;
      }
    }
    for (final Object name : info.keySet()) {
      if (name != null && key.equalsIgnoreCase(name.toString())) {
        return true;
      }
    }
    return false;
  }

  /** Flat copy: direct entries (including non-String ones) plus anything from the defaults. */
  private static Properties copyOf(final Properties info) {
    final Properties copy = new Properties();
    if (info == null) {
      return copy;
    }
    for (final Map.Entry<Object, Object> e : info.entrySet()) {
      copy.put(e.getKey(), e.getValue());
    }
    for (final String name : info.stringPropertyNames()) {
      if (!copy.containsKey(name)) {
        copy.setProperty(name, info.getProperty(name));
      }
    }
    return copy;
  }
}
