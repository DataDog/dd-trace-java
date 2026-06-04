package datadog.trace.bootstrap.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionUrlParser.extractDBInfo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

class JDBCConnectionUrlParserPasswordLeakTest {

  @TableTest({
    "scenario                                       | url                                             | type       | host     | user        ",
    "PostgreSQL userinfo with password              | jdbc:postgresql://myuser:secret123@pg.host/mydb | postgresql | pg.host  | myuser      ",
    "PostgreSQL userinfo without password           | jdbc:postgresql://myuser@pg.host/mydb           | postgresql | pg.host  | myuser      ",
    "PostgreSQL userinfo with percent-encoded colon | jdbc:postgresql://tenant%3Aalice@pg.host/mydb   | postgresql | pg.host  | tenant:alice",
    "SAP userinfo with password                     | jdbc:sap://myuser:secret@sap.host/sapdb         | sap        | sap.host | myuser      "
  })
  void passwordShouldNotLeakIntoUserTag(String url, String type, String host, String user) {
    DBInfo info = extractDBInfo(url, null);
    assertEquals(type, info.getType());
    assertEquals(host, info.getHost());
    assertEquals(user, info.getUser());
  }
}
