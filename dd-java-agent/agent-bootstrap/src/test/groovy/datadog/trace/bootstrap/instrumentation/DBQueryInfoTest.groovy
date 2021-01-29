package datadog.trace.bootstrap.instrumentation

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo
import datadog.trace.test.util.DDSpecification

class DBQueryInfoTest extends DDSpecification {
  
  def "extract operation name" () {
    when:
    DBQueryInfo info = new DBQueryInfo(UTF8BytesString.create(sql))
    then:
    info.getOperation() as String == operation

    where:
    operation         | sql
    "INSERT"          | "INSERT INTO table_name (column1, column2, column3, ...) VALUES (value1, value2, value3, ...)"
    "UPDATE"          | "UPDATE table_name SET column1 = value1, column2 = value2, WHERE condition;"
    "SELECT"          | "SELECT * FROM TABLE WHERE condition"
    "CALL"            | "{CALL STORED_PROC()}"
  }
}
