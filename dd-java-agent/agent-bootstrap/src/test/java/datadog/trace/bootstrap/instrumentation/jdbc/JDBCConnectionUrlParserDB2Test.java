package datadog.trace.bootstrap.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionUrlParser.extractDBInfo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

/**
 * Tests for DB2/AS400 JDBC URL parsing when the URL contains '=' but no explicit port.
 *
 * <p>Without a port, {@code lastIndexOf(':')} returns the scheme colon, making {@code urlPart1} too
 * short for the subsequent {@code substring()} call and causing a {@code
 * StringIndexOutOfBoundsException}.
 */
class JDBCConnectionUrlParserDB2Test {

  @TableTest({
    "scenario                             | url                                                        | type  | host     | instance | user    | db     ",
    "DB2 with user param, no port         | jdbc:db2://db2.host/mydb?user=db2user                      | db2   | db2.host | mydb     | db2user | mydb   ",
    "AS400 with user param, no port       | jdbc:as400://ashost/asdb?user=asuser                       | as400 | ashost   | asdb     | asuser  | asdb   ",
    "DB2 with multiple params, no port    | jdbc:db2://db2.host/mydb?user=db2user&connectionTimeout=30 | db2   | db2.host | mydb     | db2user | mydb   ",
    "DB2 with databasename param, no port | jdbc:db2://db2.host/mydb?user=db2user&databasename=otherdb | db2   | db2.host | mydb     | db2user | otherdb",
    "DB2 with port and colon params       | jdbc:db2://db2.host:50000/mydb:user=db2user                | db2   | db2.host | mydb     | db2user | mydb   ",
    "DB2 no params                        | jdbc:db2://db2.host/mydb                                   | db2   | db2.host | mydb     |         | mydb   "
  })
  void db2UrlWithEqualsAndNoPortShouldParseCorrectly(
      String url, String type, String host, String instance, String user, String db) {
    DBInfo info = extractDBInfo(url, null);
    assertEquals(type, info.getType());
    assertEquals(host, info.getHost());
    assertEquals(instance, info.getInstance());
    assertEquals(user, info.getUser());
    assertEquals(db, info.getDb());
  }
}
