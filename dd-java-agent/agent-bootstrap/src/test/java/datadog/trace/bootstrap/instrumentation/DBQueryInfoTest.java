package datadog.trace.bootstrap.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import datadog.trace.test.util.DDJavaSpecification;
import org.tabletest.junit.TableTest;

class DBQueryInfoTest extends DDJavaSpecification {

  @TableTest({
    "scenario                     | operation | sql                                                                                                                                        ",
    "insert                       | INSERT    | \"INSERT INTO table_name (column1, column2, column3, ...) VALUES (value1, value2, value3, ...)\"                                           ",
    "insert with sql commenter    | INSERT    | \"/*ddps='SqlCommenter',dddbs='my-service'*/ INSERT INTO table_name (column1, column2, column3, ...) VALUES (value1, value2, value3, ...)\"",
    "insert with customer comment | INSERT    | \"INSERT /*my-customer-comment*/ INTO table_name (column1, column2, column3, ...) VALUES (value1, value2, value3, ...)\"                   ",
    "update                       | UPDATE    | \"UPDATE table_name SET column1 = value1, column2 = value2, WHERE condition;\"                                                             ",
    "update with sql commenter    | UPDATE    | \"/*ddps='SqlCommenter',dddbs='my-service'*/ UPDATE table_name SET column1 = value1, column2 = value2, WHERE condition;\"                  ",
    "update with customer comment | UPDATE    | \"UPDATE /*my-customer-comment*/ table_name SET column1 = value1, column2 = value2, WHERE condition;\"                                     ",
    "select                       | SELECT    | \"SELECT * FROM TABLE WHERE condition\"                                                                                                    ",
    "select with sql commenter    | SELECT    | \"/*ddps='SqlCommenter',dddbs='my-service'*/ SELECT * FROM TABLE WHERE condition\"                                                         ",
    "call                         | CALL      | \"{CALL STORED_PROC()}\"                                                                                                                   ",
    "call with customer comment   | CALL      | \"{CALL /*my-customer-comment*/ STORED_PROC()}\"                                                                                           ",
    "call with sql commenter      | CALL      | \"/*ddps='SqlCommenter',dddbs='my-service'*/ {CALL STORED_PROC()}\"                                                                        "
  })
  void extractOperationName(String operation, String sql) {
    // when
    DBQueryInfo info = new DBQueryInfo(sql);

    // then
    assertEquals(operation, info.getOperation().toString());
  }
}
