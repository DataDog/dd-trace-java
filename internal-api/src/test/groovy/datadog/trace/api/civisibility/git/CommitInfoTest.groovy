package datadog.trace.api.civisibility.git

import datadog.trace.api.git.CommitInfo
import datadog.trace.api.git.PersonInfo
import spock.lang.Specification

class CommitInfoTest extends Specification {
  private static final PersonInfo PERSON_A = new PersonInfo("nameA", "emailA", "dateA")
  private static final PersonInfo PERSON_B = new PersonInfo("nameB", "emailB", "dateB")
  private static final PersonInfo EMPTY_PERSON = new PersonInfo(null, null, null)

  def "test isEmpty"() {
    expect:
    info.isEmpty() == empty

    where:
    info                                                   | empty
    new CommitInfo(null, EMPTY_PERSON, EMPTY_PERSON, null) | true
    new CommitInfo("", EMPTY_PERSON, EMPTY_PERSON, "")     | true
    new CommitInfo(null, EMPTY_PERSON, PERSON_A, "msg")    | false
    new CommitInfo("sha", PERSON_A, PERSON_A, "msg")       | false
  }

  def "test isComplete"() {
    expect:
    info.isComplete() == complete

    where:
    info                                                   | complete
    new CommitInfo(null, EMPTY_PERSON, EMPTY_PERSON, null) | false
    new CommitInfo("", EMPTY_PERSON, EMPTY_PERSON, "")     | false
    new CommitInfo(null, EMPTY_PERSON, PERSON_A, "msg")    | false
    new CommitInfo("sha", PERSON_A, PERSON_A, "msg")       | true
  }

  def 'test info coalesce'() {
    expect:
    CommitInfo.coalesce(infoA, infoB) == result
    where:
    infoA                                                      | infoB                                                  | result
    new CommitInfo("shaA", PERSON_A, PERSON_A, "msgA")         | new CommitInfo("shaB", PERSON_B, PERSON_B, "msgB")     | new CommitInfo("shaA", PERSON_A, PERSON_A, "msgA")
    new CommitInfo(null, EMPTY_PERSON, EMPTY_PERSON, null)     | new CommitInfo("shaB", PERSON_B, PERSON_B, "msgB")     | new CommitInfo("shaB", PERSON_B, PERSON_B, "msgB")
    new CommitInfo("shaA", EMPTY_PERSON, EMPTY_PERSON, "msgA") | new CommitInfo("shaB", PERSON_B, PERSON_B, null)       | new CommitInfo("shaA", PERSON_B, PERSON_B, "msgA")
    new CommitInfo("shaA", EMPTY_PERSON, EMPTY_PERSON, "msgA") | new CommitInfo(null, EMPTY_PERSON, EMPTY_PERSON, null) | new CommitInfo("shaA", EMPTY_PERSON, EMPTY_PERSON, "msgA")
  }
}
