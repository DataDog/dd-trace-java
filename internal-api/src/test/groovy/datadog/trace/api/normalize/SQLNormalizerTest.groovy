package datadog.trace.api.normalize

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.test.util.DDSpecification

class SQLNormalizerTest extends DDSpecification {

  def "test normalize SQL"() {
    when:
    UTF8BytesString normalized = SQLNormalizer.normalize(UTF8BytesString.create(sql))
    then:
    normalized as String == expected
    where:
    sql                                                                                                                              | expected
    "SELECT * FROM TABLE WHERE userId = 'abc1287681964'"                                                                             | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = 'abc\\'1287681964'"                                                                          | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = '\\'abc1287681964'"                                                                          | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = 'abc1287681964\\''"                                                                          | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = '\\'abc1287681964\\''"                                                                       | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = 'abc\\'1287681\\'964'"                                                                       | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = 'abc\\'1287\\'681\\'964'"                                                                    | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = 'abc\\'1287\\'681\\'\\'\\'\\'964'"                                                           | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId IN [\'a\', \'b\', \'c\']"                                                                      | "SELECT * FROM TABLE WHERE userId IN [?, ?, ?]"
    "SELECT * FROM TABLE WHERE userId IN [\'abc\\'1287681\\'964\', \'abc\\'1287\\'681\\'\\'\\'\\'964\', \'abc\\'1287\\'681\\'964\']" | "SELECT * FROM TABLE WHERE userId IN [?, ?, ?]"
    "SELECT * FROM TABLE WHERE userId = 'abc1287681964' ORDER BY FOO DESC"                                                           | "SELECT * FROM TABLE WHERE userId = ? ORDER BY FOO DESC"
    "SELECT * FROM TABLE WHERE userId = 'abc\\'1287\\'681\\'\\'\\'\\'964' ORDER BY FOO DESC"                                         | "SELECT * FROM TABLE WHERE userId = ? ORDER BY FOO DESC"
    "SELECT * FROM TABLE JOIN SOMETHING ON TABLE.foo = SOMETHING.bar"                                                                | "SELECT * FROM TABLE JOIN SOMETHING ON TABLE.foo = SOMETHING.bar"
    "CREATE TABLE \"VALUE\""                                                                                                         | "CREATE TABLE \"VALUE\""
    "INSERT INTO \"VALUE\" (\"column\") VALUES (\'ljahklshdlKASH\')"                                                                 | "INSERT INTO \"VALUE\" (\"column\") VALUES (?)"
  }
}
