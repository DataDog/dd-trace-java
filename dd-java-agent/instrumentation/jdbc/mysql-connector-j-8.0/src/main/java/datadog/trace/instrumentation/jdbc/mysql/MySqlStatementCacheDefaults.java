package datadog.trace.instrumentation.jdbc.mysql;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public final class MySqlStatementCacheDefaults {

  public static final String CACHE_PREP_STMTS = "cachePrepStmts";
  public static final String PREP_STMT_CACHE_SIZE = "prepStmtCacheSize";
  public static final String PREP_STMT_CACHE_SQL_LIMIT = "prepStmtCacheSqlLimit";

  public static final String DEFAULT_CACHE_SIZE = "250";
  public static final String DEFAULT_CACHE_SQL_LIMIT = "2048";

  private static final String[] OPT_OUT_KEYS = {
    "cacheprepstmts", "useserverprepstmts", "usecursorfetch", "useconfigs", "propertiestransform"
  };
  private static final String PREP_STMT_CACHE_SIZE_LOWER =
      PREP_STMT_CACHE_SIZE.toLowerCase(Locale.ROOT);
  private static final String PREP_STMT_CACHE_SQL_LIMIT_LOWER =
      PREP_STMT_CACHE_SQL_LIMIT.toLowerCase(Locale.ROOT);

  private MySqlStatementCacheDefaults() {}

  public static Properties withDefaults(final String url, final Properties info) {
    if (!isClassicMySqlUrl(url)
        || url.indexOf('%') >= 0
        || (info != null && info.getClass() != Properties.class)) {
      return info;
    }
    final String lowerUrl = url.toLowerCase(Locale.ROOT);
    final Set<String> lowerKeys = lowerCaseKeys(info);
    for (final String key : OPT_OUT_KEYS) {
      if (isSet(lowerUrl, lowerKeys, key)) {
        return info;
      }
    }
    final Properties copy = copyOf(info);
    copy.setProperty(CACHE_PREP_STMTS, "true");
    if (!isSet(lowerUrl, lowerKeys, PREP_STMT_CACHE_SIZE_LOWER)) {
      copy.setProperty(PREP_STMT_CACHE_SIZE, DEFAULT_CACHE_SIZE);
    }
    if (!isSet(lowerUrl, lowerKeys, PREP_STMT_CACHE_SQL_LIMIT_LOWER)) {
      copy.setProperty(PREP_STMT_CACHE_SQL_LIMIT, DEFAULT_CACHE_SQL_LIMIT);
    }
    return copy;
  }

  static boolean isClassicMySqlUrl(final String url) {
    return url != null && (url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mysql+srv:"));
  }

  private static boolean isSet(
      final String lowerUrl, final Set<String> lowerKeys, final String lowerKey) {
    return lowerUrl.contains(lowerKey) || lowerKeys.contains(lowerKey);
  }

  private static Set<String> lowerCaseKeys(final Properties info) {
    final Set<String> keys = new HashSet<>();
    if (info == null) {
      return keys;
    }
    for (final String name : info.stringPropertyNames()) {
      keys.add(name.toLowerCase(Locale.ROOT));
    }
    for (final Object name : info.keySet()) {
      if (name != null) {
        keys.add(name.toString().toLowerCase(Locale.ROOT));
      }
    }
    return keys;
  }

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
