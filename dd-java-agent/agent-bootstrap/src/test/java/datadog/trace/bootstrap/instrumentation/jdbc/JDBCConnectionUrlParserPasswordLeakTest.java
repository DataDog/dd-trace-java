package datadog.trace.bootstrap.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionUrlParser.extractDBInfo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

/**
 * Tests that passwords embedded in JDBC URL userinfo (user:password@host) do not leak into the
 * {@code db.user} span tag.
 *
 * <p>{@link java.net.URI#getUserInfo()} returns the full userinfo component, including the
 * password. Without sanitization, {@code builder.user("myuser:secret")} stores the password in
 * {@code DBInfo.user}, which is then set as the {@code db.user} span tag.
 */
class JDBCConnectionUrlParserPasswordLeakTest {

  @TableTest({
    "scenario                                       | url                                             | type       | host    | user        ",
    "PostgreSQL userinfo with password              | jdbc:postgresql://myuser:secret123@pg.host/mydb | postgresql | pg.host | myuser      ",
    "PostgreSQL userinfo without password           | jdbc:postgresql://myuser@pg.host/mydb           | postgresql | pg.host | myuser      ",
    "PostgreSQL userinfo with percent-encoded colon | jdbc:postgresql://tenant%3Aalice@pg.host/mydb   | postgresql | pg.host | tenant:alice"
  })
  void passwordShouldNotLeakIntoUserTag(String url, String type, String host, String user) {
    DBInfo info = extractDBInfo(url, null);
    assertEquals(type, info.getType());
    assertEquals(host, info.getHost());
    assertEquals(user, info.getUser());
  }
}
