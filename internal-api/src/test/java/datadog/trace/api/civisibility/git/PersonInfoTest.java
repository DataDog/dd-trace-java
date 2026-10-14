package datadog.trace.api.civisibility.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.git.PersonInfo;
import org.tabletest.junit.TableTest;

class PersonInfoTest {

  @TableTest({
    "scenario           | name | email | date | empty",
    "all null           |      |       |      | true ",
    "empty name         | ''   |       |      | true ",
    "email and date set |      | email | date | false",
    "all set            | name | email | date | false"
  })
  void testIsEmpty(String name, String email, String date, boolean empty) {
    PersonInfo info = new PersonInfo(name, email, date);

    assertEquals(empty, info.isEmpty());
  }

  @TableTest({
    "scenario      | name | email | date | complete",
    "all null      |      |       |      | false   ",
    "only name set | name |       |      | false   ",
    "all empty     | ''   | ''    | ''   | false   ",
    "all set       | name | email | date | true    "
  })
  void testIsComplete(String name, String email, String date, boolean complete) {
    PersonInfo info = new PersonInfo(name, email, date);

    assertEquals(complete, info.isComplete());
  }

  @TableTest({
    "scenario                | nameA | emailA | dateA | nameB | emailB | dateB | expectedName | expectedEmail | expectedDate",
    "first complete          | nameA | emailA | dateA | nameB | emailB | dateB | nameA        | emailA        | dateA       ",
    "first empty             |       |        |       | nameB | emailB | dateB | nameB        | emailB        | dateB       ",
    "email taken from second | nameA |        | dateA | nameB | emailB |       | nameA        | emailB        | dateA       ",
    "second empty            | nameA |        | dateA |       |        |       | nameA        |               | dateA       "
  })
  void testInfoCoalesce(
      String nameA,
      String emailA,
      String dateA,
      String nameB,
      String emailB,
      String dateB,
      String expectedName,
      String expectedEmail,
      String expectedDate) {
    PersonInfo infoA = new PersonInfo(nameA, emailA, dateA);
    PersonInfo infoB = new PersonInfo(nameB, emailB, dateB);
    PersonInfo expected = new PersonInfo(expectedName, expectedEmail, expectedDate);

    assertEquals(expected, PersonInfo.coalesce(infoA, infoB));
  }
}
