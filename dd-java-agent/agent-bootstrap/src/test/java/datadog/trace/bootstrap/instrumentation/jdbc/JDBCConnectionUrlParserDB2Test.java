package datadog.trace.bootstrap.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionUrlParser.extractDBInfo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for DB2/AS400 JDBC URL parsing when the URL contains '=' but no explicit port.
 *
 * <p>Without a port, {@code lastIndexOf(':')} returns the scheme colon, making {@code urlPart1} too
 * short for the subsequent {@code substring()} call and causing a {@code
 * StringIndexOutOfBoundsException}.
 */
class JDBCConnectionUrlParserDB2Test {

  @ParameterizedTest
  @CsvSource({
    // DB2: path present, query-string params, no port — last ':' is the scheme colon
    "jdbc:db2://db2.host/mydb?user=db2user,                       db2,   db2.host, mydb, db2user",
    // AS400: same pattern
    "jdbc:as400://ashost/asdb?user=asuser,                         as400, ashost,   asdb, asuser",
    // DB2: multiple query params
    "jdbc:db2://db2.host/mydb?user=db2user&connectionTimeout=30,  db2,   db2.host, mydb, db2user",
  })
  void db2UrlWithEqualsAndNoPortShouldParseHostCorrectly(
      String url,
      String expectedType,
      String expectedHost,
      String expectedInstance,
      String expectedUser) {
    DBInfo info = extractDBInfo(url.trim(), null);

    // Without the fix, host was never extracted due to StringIndexOutOfBoundsException.
    assertEquals(expectedType, info.getType());
    assertEquals(expectedHost, info.getHost());
    assertEquals(expectedInstance, info.getInstance());
    assertEquals(expectedUser, info.getUser());
  }

  @ParameterizedTest
  @CsvSource({
    // databasename param in query string should be parsed into db field
    "jdbc:db2://db2.host/mydb?user=db2user&databasename=otherdb, db2, db2.host, db2user, otherdb",
  })
  void db2UrlWithDatabasenameQueryParamShouldParseDbCorrectly(
      String url,
      String expectedType,
      String expectedHost,
      String expectedUser,
      String expectedDb) {
    DBInfo info = extractDBInfo(url.trim(), null);

    assertEquals(expectedType, info.getType());
    assertEquals(expectedHost, info.getHost());
    assertEquals(expectedUser, info.getUser());
    assertEquals(expectedDb, info.getDb());
  }
}
