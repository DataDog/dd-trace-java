package datadog.trace.bootstrap.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionUrlParser.extractDBInfo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * Tests for Oracle {@code @} connect strings that previously threw {@code NumberFormatException}.
 *
 * <p>A second ':' after the port (EZConnect {@code :server_type}, IPv6 literals, {@code ?params})
 * or a protocol prefix other than {@code ldap://} was taken as the {@code host:port:sid} form, so a
 * non-numeric segment was parsed as the port and the whole URL fell back to defaults.
 */
class JDBCConnectionUrlParserOracleTest {

  @TableTest({
    "scenario                       | url                                                                 | host      | port | instance",
    "SID form                       | jdbc:oracle:thin:@orcl.host:55:orclsn                               | orcl.host | 55   | orclsn  ",
    "service form                   | jdbc:oracle:thin:@//orcl.host:55/orclsn                             | orcl.host | 55   | orclsn  ",
    "service with server type       | jdbc:oracle:thin:@//orcl.host:55/orclsn:dedicated                   | orcl.host | 55   | orclsn  ",
    "service with type and instance | jdbc:oracle:thin:@//orcl.host:55/orclsn:pooled/inst1                | orcl.host | 55   | orclsn  ",
    "service with instance name     | jdbc:oracle:thin:@//orcl.host:55/orclsn/inst1                       | orcl.host | 55   | orclsn  ",
    "service without port           | jdbc:oracle:thin:@//orcl.host/orclsn:dedicated                      | orcl.host | 1521 | orclsn  ",
    "tcps protocol                  | jdbc:oracle:thin:@tcps://orcl.host:2484/orclsn                      | orcl.host | 2484 | orclsn  ",
    "tcp protocol                   | jdbc:oracle:thin:@tcp://orcl.host:55/orclsn                         | orcl.host | 55   | orclsn  ",
    "EZConnect Plus params          | jdbc:oracle:thin:@tcps://orcl.host:2484/orclsn?wallet_location=c:/w | orcl.host | 2484 | orclsn  ",
    "IPv6 literal                   | jdbc:oracle:thin:@//[::1]:55/orclsn                                 | '[::1]'   | 55   | orclsn  ",
    "non-numeric port               | jdbc:oracle:thin:@orcl.host:abc:orclsn                              | orcl.host | 1521 | orclsn  ",
    "leading plus on port           | jdbc:oracle:thin:@orcl.host:+55:orclsn                              | orcl.host | 55   | orclsn  "
  })
  void parsesOracleConnectStrings(String url, String host, Integer port, String instance) {
    DBInfo info = extractDBInfo(url, null);
    assertEquals("oracle", info.getType());
    assertEquals("thin", info.getSubtype());
    assertEquals(host, info.getHost());
    assertEquals(port, info.getPort());
    assertEquals(instance, info.getInstance());
  }

  @Test
  void keepsAnLdapDistinguishedNameWhole() {
    // '/' and ':' are legal inside an LDAP name; only EZConnect suffixes are stripped
    DBInfo info =
        extractDBInfo(
            "jdbc:oracle:thin:@ldap://orcl.host:389/cn=orcl,cn=OracleContext,ou=R/D,dc=example,dc=com",
            null);
    assertEquals("orcl.host", info.getHost());
    assertEquals(389, info.getPort());
    assertEquals("cn=orcl,cn=oraclecontext,ou=r/d,dc=example,dc=com", info.getInstance());
    assertEquals(info.getInstance(), info.getDb());
  }
}
