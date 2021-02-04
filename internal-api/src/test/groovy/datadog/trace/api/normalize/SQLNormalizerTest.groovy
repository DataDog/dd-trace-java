package datadog.trace.api.normalize

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.test.util.DDSpecification

class SQLNormalizerTest extends DDSpecification {

  def "test normalize SQL"() {
    when:
    UTF8BytesString normalized = SQLNormalizer.normalize(sql)
    then:
    normalized as String == expected
    where:
    sql                                                                                                                              | expected
    ""                                                                                                                               | ""
    "   "                                                                                                                            | "   "
    "         "                                                                                                                      | "         "
    "罿"                                                                                                                              | "罿"
    "罿潯"                                                                                                                             | "罿潯"
    "罿潯罿潯罿潯罿潯罿潯"                                                                                                                     | "罿潯罿潯罿潯罿潯罿潯"
    "SELECT * FROM TABLE WHERE userId = 'abc1287681964'"                                                                             | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = 'abc\\'1287681964'"                                                                          | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = '\\'abc1287681964'"                                                                          | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = 'abc1287681964\\''"                                                                          | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = '\\'abc1287681964\\''"                                                                       | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = 'abc\\'1287681\\'964'"                                                                       | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = 'abc\\'1287\\'681\\'964'"                                                                    | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = 'abc\\'1287\\'681\\'\\'\\'\\'964'"                                                           | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId IN (\'a\', \'b\', \'c\')"                                                                      | "SELECT * FROM TABLE WHERE userId IN (?, ?, ?)"
    "SELECT * FROM TABLE WHERE userId IN (\'abc\\'1287681\\'964\', \'abc\\'1287\\'681\\'\\'\\'\\'964\', \'abc\\'1287\\'681\\'964\')" | "SELECT * FROM TABLE WHERE userId IN (?, ?, ?)"
    "SELECT * FROM TABLE WHERE userId = 'abc1287681964' ORDER BY FOO DESC"                                                           | "SELECT * FROM TABLE WHERE userId = ? ORDER BY FOO DESC"
    "SELECT * FROM TABLE WHERE userId = 'abc\\'1287\\'681\\'\\'\\'\\'964' ORDER BY FOO DESC"                                         | "SELECT * FROM TABLE WHERE userId = ? ORDER BY FOO DESC"
    "SELECT * FROM TABLE JOIN SOMETHING ON TABLE.foo = SOMETHING.bar"                                                                | "SELECT * FROM TABLE JOIN SOMETHING ON TABLE.foo = SOMETHING.bar"
    "CREATE TABLE \"VALUE\""                                                                                                         | "CREATE TABLE \"VALUE\""
    "INSERT INTO \"VALUE\" (\"column\") VALUES (\'ljahklshdlKASH\')"                                                                 | "INSERT INTO \"VALUE\" (\"column\") VALUES (?)"
    "INSERT INTO \"VALUE\" (\"col1\",\"col2\",\"col3\") VALUES (\'blah\',12983,X'ff')"                                               | "INSERT INTO \"VALUE\" (\"col1\",\"col2\",\"col3\") VALUES (?,?,?)"
    "INSERT INTO \"VALUE\" (\"col1\", \"col2\", \"col3\") VALUES (\'blah\',12983,X'ff')"                                             | "INSERT INTO \"VALUE\" (\"col1\", \"col2\", \"col3\") VALUES (?,?,?)"
    "INSERT INTO VALUE (col1,col2,col3) VALUES (\'blah\',12983,X'ff')"                                                               | "INSERT INTO VALUE (col1,col2,col3) VALUES (?,?,?)"
    "INSERT INTO VALUE (col1,col2,col3) VALUES (12983,X'ff',\'blah\')"                                                               | "INSERT INTO VALUE (col1,col2,col3) VALUES (?,?,?)"
    "INSERT INTO VALUE (col1,col2,col3) VALUES (X'ff',\'blah\',12983)"                                                               | "INSERT INTO VALUE (col1,col2,col3) VALUES (?,?,?)"
    "INSERT INTO VALUE (col1,col2,col3) VALUES ('a',\'b\',1)"                                                                        | "INSERT INTO VALUE (col1,col2,col3) VALUES (?,?,?)"
    "INSERT INTO VALUE (col1, col2, col3) VALUES ('a',\'b\',1)"                                                                      | "INSERT INTO VALUE (col1, col2, col3) VALUES (?,?,?)"
    "INSERT INTO VALUE ( col1, col2, col3 ) VALUES ('a',\'b\',1)"                                                                    | "INSERT INTO VALUE ( col1, col2, col3 ) VALUES (?,?,?)"
    "INSERT INTO VALUE (col1,col2,col3) VALUES ('a', \'b\' ,1)"                                                                      | "INSERT INTO VALUE (col1,col2,col3) VALUES (?, ? ,?)"
    "INSERT INTO VALUE (col1, col2, col3) VALUES ('a', \'b\', 1)"                                                                    | "INSERT INTO VALUE (col1, col2, col3) VALUES (?, ?, ?)"
    "INSERT INTO VALUE ( col1, col2, col3 ) VALUES ('a', \'b\', 1)"                                                                  | "INSERT INTO VALUE ( col1, col2, col3 ) VALUES (?, ?, ?)"
    "INSERT INTO VALUE (col1,col2,col3) VALUES (X'ff',\'罿潯罿潯罿潯罿潯罿潯\',12983)"                                                         | "INSERT INTO VALUE (col1,col2,col3) VALUES (?,?,?)"
    "INSERT INTO VALUE (col1,col2,col3) VALUES (X'ff',\'罿\',12983)"                                                                  | "INSERT INTO VALUE (col1,col2,col3) VALUES (?,?,?)"
    "SELECT 3 AS NUCLEUS_TYPE,A0.ID,A0.\"NAME\" FROM \"VALUE\" A0"                                                                   | "SELECT ? AS NUCLEUS_TYPE,A0.ID,A0.\"NAME\" FROM \"VALUE\" A0"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > .9999"                                      | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > 0.9999"                                     | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > -0.9999"                                    | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> \'\'"                                      | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> ?"
    "CREATE TABLE S_H2 (id INTEGER not NULL, PRIMARY KEY ( id ))"                                                                    | "CREATE TABLE S_H2 (id INTEGER not NULL, PRIMARY KEY ( id ))"
  }
}
