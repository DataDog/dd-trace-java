package datadog.trace.bootstrap.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionUrlParser.extractDBInfo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

/**
 * Tests for jTDS URLs ({@code jdbc:jtds:<type>://<server>[:<port>][/<db>][;<props>]}).
 *
 * <p>The port and database separators used to be searched for across the whole remainder of the
 * URL, starting at an offset unrelated to it, so a ':' or '/' in a property value or a short host
 * name produced a {@code StringIndexOutOfBoundsException} or a wrong host.
 */
class JDBCConnectionUrlParserJtdsTest {

  @TableTest({
    "scenario                   | url                                                | subtype   | host    | port | db  ",
    "host port and db           | jdbc:jtds:sqlserver://dbhost:1500/mydb             | sqlserver | dbhost  | 1500 | mydb",
    "colon in property after db | jdbc:jtds:sqlserver://dbhost:1500/mydb;appname=a:b | sqlserver | dbhost  | 1500 | mydb",
    "colon in property, no port | jdbc:jtds:sqlserver://dbhost/mydb;password=p:w     | sqlserver | dbhost  | 1433 | mydb",
    "colon in property, no db   | jdbc:jtds:sqlserver://dbhost;password=p:w          | sqlserver | dbhost  | 1433 |     ",
    "slash in property, no db   | jdbc:jtds:sqlserver://dbhost;password=p/w          | sqlserver | dbhost  | 1433 |     ",
    "short host with port       | jdbc:jtds:sqlserver://db:1500/mydb                 | sqlserver | db      | 1500 | mydb",
    "sybase with port           | jdbc:jtds:sybase://dbhost:7200/mydb                | sybase    | dbhost  | 7200 | mydb",
    "IPv6 literal with port     | jdbc:jtds:sqlserver://[::1]:1500/mydb              | sqlserver | '[::1]' | 1500 | mydb",
    "missing protocol separator | jdbc:jtds:sqlserver:dbhost                         |           |         |      |     "
  })
  void parsesJtdsUrls(String url, String subtype, String host, Integer port, String db) {
    DBInfo info = extractDBInfo(url, null);
    assertEquals("jtds", info.getType());
    assertEquals(subtype, info.getSubtype());
    assertEquals(host, info.getHost());
    assertEquals(port, info.getPort());
    assertEquals(db, info.getDb());
  }
}
