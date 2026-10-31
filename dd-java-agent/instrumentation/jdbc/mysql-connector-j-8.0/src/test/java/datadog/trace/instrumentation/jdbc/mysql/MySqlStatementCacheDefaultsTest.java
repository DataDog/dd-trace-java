package datadog.trace.instrumentation.jdbc.mysql;

import static datadog.trace.instrumentation.jdbc.mysql.MySqlStatementCacheDefaults.withDefaults;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class MySqlStatementCacheDefaultsTest {

  private static final String URL = "jdbc:mysql://localhost:3306/db";

  @TableTest({
    "Scenario              | Url                                                         ",
    "single host           | jdbc:mysql://localhost:3306/db                              ",
    "with unrelated params | jdbc:mysql://h/db?useSSL=false&rewriteBatchedStatements=true",
    "failover              | jdbc:mysql://a:3306,b:3306/db                               ",
    "load balance          | jdbc:mysql:loadbalance://a,b/db                             ",
    "replication           | jdbc:mysql:replication://a,b/db                             ",
    "dns srv               | jdbc:mysql+srv://cluster.example.com/db                     "
  })
  void addsCacheSettingsToMySqlUrls(final String url) {
    final Properties info = props("user", "u", "password", "p");

    final Properties result = withDefaults(url, info);

    assertNotSame(info, result);
    assertEquals("true", result.getProperty("cachePrepStmts"));
    assertEquals("250", result.getProperty("prepStmtCacheSize"));
    assertEquals("2048", result.getProperty("prepStmtCacheSqlLimit"));
    assertEquals("u", result.getProperty("user"));
    assertEquals("p", result.getProperty("password"));
    assertNull(result.getProperty("useServerPrepStmts"));
  }

  @TableTest({
    "Scenario    | Url                             ",
    "postgres    | jdbc:postgresql://localhost/db  ",
    "mariadb     | jdbc:mariadb://localhost/db     ",
    "x devapi    | mysqlx://localhost:33060/db     ",
    "jdbc mysqlx | jdbc:mysqlx://localhost:33060/db",
    "empty       | ''                              ",
    "null        |                                 "
  })
  void leavesOtherUrlsAlone(final String url) {
    final Properties info = props("user", "u");
    assertSame(info, withDefaults(url, info));
  }

  @TableTest({
    "Scenario                       | Url                                                        ",
    "cache off in url               | jdbc:mysql://h/db?cachePrepStmts=false                     ",
    "cache on in url                | jdbc:mysql://h/db?cachePrepStmts=true                      ",
    "cache in url, other case       | jdbc:mysql://h/db?CACHEPREPSTMTS=false                     ",
    "cache as host property         | jdbc:mysql://(host=h,port=3306,cachePrepStmts=false)/db    ",
    "cache as address property      | jdbc:mysql://address=(host=h)(cachePrepStmts=false)/db     ",
    "server prepared statements     | jdbc:mysql://h/db?useServerPrepStmts=true                  ",
    "server prepared statements off | jdbc:mysql://h/db?useServerPrepStmts=false                 ",
    "cursor fetch                   | jdbc:mysql://h/db?useCursorFetch=true                      ",
    "config bundle                  | jdbc:mysql://h/db?useConfigs=maxPerformance                ",
    "properties transform           | jdbc:mysql://h/db?propertiesTransform=com.example.Transform",
    "encoded cache off              | jdbc:mysql://h/db?%63achePrepStmts=false                   ",
    "encoded cursor fetch           | jdbc:mysql://h/db?%75seCursorFetch=true                    ",
    "encoded cache size             | jdbc:mysql://h/db?prepStmt%43acheSize=50                   ",
    "encoded host property          | jdbc:mysql://(host=h,%63achePrepStmts=false)/db            "
  })
  void respectsOptOutsInUrl(final String url) {
    final Properties info = props("user", "u");
    assertSame(info, withDefaults(url, info));
    assertEquals(props("user", "u"), info);
  }

  @TableTest({
    "Scenario                   | Key                 | Value    ",
    "cache off                  | cachePrepStmts      | false    ",
    "cache on                   | cachePrepStmts      | true     ",
    "cache off, other case      | CachePrepStmts      | false    ",
    "server prepared statements | useServerPrepStmts  | true     ",
    "cursor fetch               | useCursorFetch      | true     ",
    "config bundle              | useConfigs          | fullDebug",
    "properties transform       | propertiesTransform | x.Y      "
  })
  void respectsOptOutsInProperties(final String key, final String value) {
    final Properties info = props("user", "u", key, value);
    assertSame(info, withDefaults(URL, info));
  }

  @Test
  void respectsOptOutInPropertiesDefaults() {
    final Properties info = new Properties(props("cachePrepStmts", "false"));
    assertSame(info, withDefaults(URL, info));
  }

  @Test
  void respectsOptOutWithNonStringValue() {
    final Properties info = new Properties();
    info.put("cachePrepStmts", Boolean.FALSE);
    assertSame(info, withDefaults(URL, info));
  }

  @Test
  void neverModifiesCallerProperties() {
    final Properties defaults = props("password", "p");
    final Properties info = new Properties(defaults);
    info.setProperty("user", "u");
    final Object nonString = new Object();
    info.put("custom", nonString);

    final Properties result = withDefaults(URL, info);

    assertNotSame(info, result);
    assertEquals(2, info.size());
    assertEquals(1, defaults.size());
    assertNull(info.getProperty("cachePrepStmts"));
    assertEquals("u", result.getProperty("user"));
    assertEquals("p", result.getProperty("password"));
    assertSame(nonString, result.get("custom"));
  }

  @Test
  void keepsExplicitCacheSizeFromProperties() {
    final Properties result =
        withDefaults(URL, props("prepStmtCacheSize", "50", "prepStmtCacheSqlLimit", "512"));
    assertEquals("true", result.getProperty("cachePrepStmts"));
    assertEquals("50", result.getProperty("prepStmtCacheSize"));
    assertEquals("512", result.getProperty("prepStmtCacheSqlLimit"));
  }

  @Test
  void doesNotOverrideCacheSizeFromUrl() {
    final Properties result =
        withDefaults(URL + "?prepStmtCacheSize=50&PREPSTMTCACHESQLLIMIT=512", new Properties());
    assertEquals("true", result.getProperty("cachePrepStmts"));
    assertNull(result.getProperty("prepStmtCacheSize"));
    assertNull(result.getProperty("prepStmtCacheSqlLimit"));
  }

  @Test
  void leavesPropertiesSubclassesAlone() {
    final Properties info =
        new Properties() {
          @Override
          public String getProperty(final String key) {
            return "password".equals(key) ? "decrypted" : super.getProperty(key);
          }
        };
    info.setProperty("password", "ENC(abc)");
    assertSame(info, withDefaults(URL, info));
  }

  @Test
  void handlesNullProperties() {
    final Properties result = withDefaults(URL, null);
    assertEquals(props("cachePrepStmts", "true", "prepStmtCacheSize", "250"), withoutLimit(result));
  }

  private static Properties withoutLimit(final Properties p) {
    final Properties copy = new Properties();
    copy.putAll(p);
    assertEquals("2048", copy.remove("prepStmtCacheSqlLimit"));
    return copy;
  }

  private static Properties props(final String... kv) {
    final Properties p = new Properties();
    for (int i = 0; i < kv.length; i += 2) {
      p.setProperty(kv[i], kv[i + 1]);
    }
    return p;
  }
}
