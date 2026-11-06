package datadog.trace.instrumentation.azure.cosmos;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class CosmosResourcePathsTest {

  @TableTest({
    "Scenario                  | Path                                    | Expected                       ",
    "empty                     | ''                                      | ''                             ",
    "null                      |                                         |                                ",
    "container                 | /dbs/myDb/colls/myColl                  | /dbs/myDb/colls/myColl         ",
    "document trailing slash   | /dbs/myDb/colls/myColl/docs/item1/      | /dbs/myDb/colls/myColl/docs/?/ ",
    "document feed             | /dbs/myDb/colls/myColl/docs/            | /dbs/myDb/colls/myColl/docs/   ",
    "users and permissions     | /dbs/myDb/users/user1/permissions/perm1 | /dbs/myDb/users/?/permissions/?",
    "no leading slash          | dbs/myDb/colls/myColl/docs/item1        | dbs/myDb/colls/myColl/docs/?   ",
    "keys are case insensitive | /DBS/myDb/COLLS/myColl/docs/item1       | /DBS/myDb/COLLS/myColl/docs/?  ",
    "databases feed            | /dbs                                    | /dbs                           ",
    "collections feed          | /dbs/myDb/colls/                        | /dbs/myDb/colls/               "
  })
  void normalize(String path, String expected) {
    assertEquals(expected, CosmosResourcePaths.normalize(path));
  }

  @TableTest({
    "Scenario                  | Path                               | Database | Container",
    "empty                     | ''                                 |          |          ",
    "null                      |                                    |          |          ",
    "databases feed            | /dbs                               |          |          ",
    "database                  | /dbs/myDb                          | myDb     |          ",
    "database trailing slash   | /dbs/myDb/                         | myDb     |          ",
    "collections feed          | /dbs/myDb/colls/                   | myDb     |          ",
    "container                 | /dbs/myDb/colls/myColl             | myDb     | myColl   ",
    "document                  | /dbs/myDb/colls/myColl/docs/item1/ | myDb     | myColl   ",
    "no leading slash          | dbs/myDb/colls/myColl/docs/item1   | myDb     | myColl   ",
    "keys are case insensitive | /DBS/myDb/COLLS/myColl             | myDb     | myColl   ",
    "users are not containers  | /dbs/myDb/users/user1              | myDb     |          ",
    "not a database path       | /offers/offer1                     |          |          ",
    "bare slash                | /                                  |          |          "
  })
  void databaseAndContainer(String path, String database, String container) {
    assertArrayEquals(
        new String[] {database, container}, CosmosResourcePaths.databaseAndContainer(path));
  }

  @TableTest({
    "Scenario           | Body                                      | Id  ",
    "simple             | '{\"id\":\"myDb\"}'                       | myDb",
    "whitespace         | '{ \"id\" :  \"myDb\" }'                  | myDb",
    "other fields first | '{\"ttl\":5,\"id\":\"myDb\"}'             | myDb",
    "no id              | '{\"name\":\"myDb\"}'                     |     ",
    "id is not a string | '{\"id\":42}'                             |     ",
    "unterminated       | '{\"id\":\"myDb'                          |     ",
    "missing colon      | '{\"id\" \"myDb\"}'                       |     ",
    "empty              | ''                                        |     ",
    "nested id first    | '{\"a\":{\"id\":\"x\"},\"id\":\"myDb\"}'  | myDb",
    "id inside a value  | '{\"a\":\"\\\"id\\\":\",\"id\":\"myDb\"}' | myDb"
  })
  void idFromBody(String body, String id) {
    assertEquals(id, CosmosResourcePaths.idFromBody(body.getBytes(UTF_8)));
  }

  @Test
  void idFromBodyWithEscapedQuote() {
    assertEquals("my\"Db", CosmosResourcePaths.idFromBody("{\"id\":\"my\\\"Db\"}".getBytes(UTF_8)));
  }

  @Test
  void idFromNullBody() {
    assertNull(CosmosResourcePaths.idFromBody(null));
  }
}
