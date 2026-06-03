package datadog.trace.bootstrap.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionUrlParser.extractDBInfo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for DB2/AS400 JDBC URL parsing when the URL contains '=' but no explicit port.
 *
 * <p>Without a port, {@code lastIndexOf(':')} returns the scheme colon, making {@code urlPart1}
 * too short for the subsequent {@code substring()} call and causing a
 * {@code StringIndexOutOfBoundsException}.
 */
class JDBCConnectionUrlParserDB2Test {

  @ParameterizedTest
  @CsvSource({
    // DB2: path present, query-string params, no port — last ':' is the scheme colon
    "jdbc:db2://db2.host/mydb?user=db2user,       db2,   db2.host",
    // AS400: same pattern
    "jdbc:as400://ashost/asdb?user=asuser,         as400, ashost",
    // DB2: multiple query params
    "jdbc:db2://db2.host/mydb?user=db2user&connectionTimeout=30, db2, db2.host",
  })
  void db2UrlWithEqualsAndNoPortShouldParseHostCorrectly(
      String url, String expectedType, String expectedHost) {
    DBInfo info = extractDBInfo(url.trim(), null);

    // Without the fix, host was never extracted due to StringIndexOutOfBoundsException.
    assertEquals(expectedType, info.getType());
    assertEquals(expectedHost, info.getHost());
  }
}
