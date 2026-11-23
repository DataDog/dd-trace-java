package datadog.trace.api.civisibility.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.PersonInfo;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverter;

public class CommitInfoTest {
  private static final PersonInfo PERSON_A = new PersonInfo("nameA", "emailA", "dateA");
  private static final PersonInfo PERSON_B = new PersonInfo("nameB", "emailB", "dateB");
  private static final PersonInfo EMPTY_PERSON = new PersonInfo(null, null, null);

  @TableTest({
    "scenario                  | sha | author       | committer    | message | empty",
    "all null                  |     | EMPTY_PERSON | EMPTY_PERSON |         | true ",
    "all empty                 | ''  | EMPTY_PERSON | EMPTY_PERSON | ''      | true ",
    "committer and message set |     | EMPTY_PERSON | PERSON_A     | msg     | false",
    "all set                   | sha | PERSON_A     | PERSON_A     | msg     | false"
  })
  void testIsEmpty(
      String sha, PersonInfo author, PersonInfo committer, String message, boolean empty) {
    CommitInfo info = new CommitInfo(sha, author, committer, message);

    assertEquals(empty, info.isEmpty());
  }

  @TableTest({
    "scenario                  | sha | author       | committer    | message | complete",
    "all null                  |     | EMPTY_PERSON | EMPTY_PERSON |         | false   ",
    "all empty                 | ''  | EMPTY_PERSON | EMPTY_PERSON | ''      | false   ",
    "committer and message set |     | EMPTY_PERSON | PERSON_A     | msg     | false   ",
    "all set                   | sha | PERSON_A     | PERSON_A     | msg     | true    "
  })
  void testIsComplete(
      String sha, PersonInfo author, PersonInfo committer, String message, boolean complete) {
    CommitInfo info = new CommitInfo(sha, author, committer, message);

    assertEquals(complete, info.isComplete());
  }

  @TableTest({
    "scenario                  | shaA | authorA      | committerA   | messageA | shaB | authorB      | committerB   | messageB | expectedSha | expectedAuthor | expectedCommitter | expectedMessage",
    "first complete            | shaA | PERSON_A     | PERSON_A     | msgA     | shaB | PERSON_B     | PERSON_B     | msgB     | shaA        | PERSON_A       | PERSON_A          | msgA           ",
    "first empty               |      | EMPTY_PERSON | EMPTY_PERSON |          | shaB | PERSON_B     | PERSON_B     | msgB     | shaB        | PERSON_B       | PERSON_B          | msgB           ",
    "persons taken from second | shaA | EMPTY_PERSON | EMPTY_PERSON | msgA     | shaB | PERSON_B     | PERSON_B     |          | shaA        | PERSON_B       | PERSON_B          | msgA           ",
    "second empty              | shaA | EMPTY_PERSON | EMPTY_PERSON | msgA     |      | EMPTY_PERSON | EMPTY_PERSON |          | shaA        | EMPTY_PERSON   | EMPTY_PERSON      | msgA           "
  })
  void testInfoCoalesce(
      String shaA,
      PersonInfo authorA,
      PersonInfo committerA,
      String messageA,
      String shaB,
      PersonInfo authorB,
      PersonInfo committerB,
      String messageB,
      String expectedSha,
      PersonInfo expectedAuthor,
      PersonInfo expectedCommitter,
      String expectedMessage) {
    CommitInfo infoA = new CommitInfo(shaA, authorA, committerA, messageA);
    CommitInfo infoB = new CommitInfo(shaB, authorB, committerB, messageB);
    CommitInfo expected =
        new CommitInfo(expectedSha, expectedAuthor, expectedCommitter, expectedMessage);

    assertEquals(expected, CommitInfo.coalesce(infoA, infoB));
  }

  /** Resolves the {@link PersonInfo} constants by name. */
  @TypeConverter
  public static PersonInfo toPersonInfo(String value) {
    switch (value) {
      case "PERSON_A":
        return PERSON_A;
      case "PERSON_B":
        return PERSON_B;
      case "EMPTY_PERSON":
        return EMPTY_PERSON;
      default:
        throw new IllegalArgumentException("Unknown person: " + value);
    }
  }
}
