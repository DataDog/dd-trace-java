package datadog.trace.bootstrap.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionUrlParser.DB2;
import static datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionUrlParser.extractDBInfo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

  /**
   * A URL without "//" after the type, whose only "://" is inside a ';' property value, used to
   * throw a {@code StringIndexOutOfBoundsException}. Calls {@code DB2.doParse} directly since
   * {@code extractDBInfo} swallows parse exceptions.
   */
  @TableTest({
    "scenario                        | url                                                   | type ",
    "DB2 with URL in property value  | db2:mydb;x=http://y                                   | db2  ",
    "AS400 with file URL in property | as400:host;ssltruststore=file://x                     | as400",
    "AS400 with several properties   | as400:host;libraries=a;secure=true;keystore=file:///x | as400",
    "Empty type suffix with property | db2:;a=b://h                                          | db2  "
  })
  void schemeSeparatorOnlyInPropertyValueShouldNotThrow(String url, String type) {
    DBInfo info = DB2.doParse(url, DBInfo.DEFAULT.toBuilder().type(type)).build();
    assertEquals(type, info.getType());
    assertNull(info.getHost());
    assertEquals(50000, info.getPort());
    assertNull(info.getInstance());
  }
}
