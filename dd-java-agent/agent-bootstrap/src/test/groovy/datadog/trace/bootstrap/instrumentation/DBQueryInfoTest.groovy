package datadog.trace.bootstrap.instrumentation

import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo
import datadog.trace.test.util.DDSpecification

class DBQueryInfoTest extends DDSpecification {

  def "extract operation name"() {
    when:
    DBQueryInfo info = new DBQueryInfo(sql, stripComment)
    then:
    info.getOperation() as String == operation

    where:
    operation | sql                                                                                                             | stripComment
    "INSERT"  | "INSERT INTO table_name (column1, column2, column3, ...) VALUES (value1, value2, value3, ...)"                  | false
    "INSERT"  | "/*test-comment*/ INSERT INTO table_name (column1, column2, column3, ...) VALUES (value1, value2, value3, ...)" | true
    "UPDATE"  | "UPDATE table_name SET column1 = value1, column2 = value2, WHERE condition;"                                    | false
    "UPDATE"  | "/*test-comment*/ UPDATE table_name SET column1 = value1, column2 = value2, WHERE condition;"                   | true
    "SELECT"  | "SELECT * FROM TABLE WHERE condition"                                                                           | false
    "SELECT"  | "/*test-comment*/ SELECT * FROM TABLE WHERE condition"                                                          | true
    "CALL"    | "{CALL STORED_PROC()}"                                                                                          | false
    "CALL"    | "/*test-comment*/ {CALL STORED_PROC()}"                                                                         | true
  }
}
