package datadog.trace.bootstrap.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionUrlParser.extractDBInfo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Regression tests for StringIndexOutOfBoundsException in MODIFIED_URL_LIKE.doParse() (line 109)
 * when parsing DB2/AS400 URLs that contain '=' but have no explicit port number.
 *
 * <p>Root cause: {@code lastIndexOf(':')} returns the scheme colon (e.g. position 3 in
 * {@code "db2://..."}) when there is no port in the URL. This makes {@code urlPart1} shorter than
 * {@code hostIndex + 3}, causing the subsequent {@code urlPart1.substring(hostIndex + 3)} call to
 * throw.
 *
 * <p>Triggering condition: type is {@code db2} or {@code as400}, URL contains {@code =} (e.g.
 * query-string parameters), and there is no explicit port.
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

    // Before the fix, MODIFIED_URL_LIKE.doParse() throws StringIndexOutOfBoundsException.
    // The exception is swallowed by the catch in JDBCConnectionUrlParser.parse(), but host
    // is never set, so info.getHost() returns null.
    assertEquals(expectedType, info.getType());
    assertEquals(expectedHost, info.getHost());
  }
}
