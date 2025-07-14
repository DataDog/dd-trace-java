package datadog.trace.civisibility.ci

import datadog.trace.api.git.CommitInfo
import datadog.trace.api.git.PersonInfo
import spock.lang.Specification

class PullRequestInfoTest extends Specification {
  private static final PersonInfo PERSON_A = new PersonInfo("nameA", "emailA", "dateA")
  private static final PersonInfo PERSON_B = new PersonInfo("nameB", "emailB", "dateB")
  private static final PersonInfo EMPTY_PERSON = new PersonInfo(null, null, null)
  private static final CommitInfo COMMIT_A = new CommitInfo("shaA", PERSON_A, PERSON_A, "msgA")
  private static final CommitInfo COMMIT_B = new CommitInfo("shaB", PERSON_B, PERSON_B, "msgB")
  private static final CommitInfo EMPTY_COMMIT = new CommitInfo(null, EMPTY_PERSON, EMPTY_PERSON, null)

  def "test isEmpty"() {
    expect:
    info.isEmpty() == empty

    where:
    info                                                     | empty
    new PullRequestInfo(null, null, EMPTY_COMMIT, null)      | true
    new PullRequestInfo("", "", EMPTY_COMMIT, "")            | true
    new PullRequestInfo(null, "", COMMIT_A, "42")            | false
    new PullRequestInfo("branch", "baseSha", COMMIT_A, "42") | false
  }

  def "test isComplete"() {
    expect:
    info.isComplete() == empty

    where:
    info                                                     | empty
    new PullRequestInfo(null, null, EMPTY_COMMIT, null)      | false
    new PullRequestInfo("", "", EMPTY_COMMIT, "")            | false
    new PullRequestInfo(null, "", COMMIT_A, "42")            | false
    new PullRequestInfo("branch", "baseSha", COMMIT_A, "42") | true
  }

  def "test info merge"() {
    expect:
    PullRequestInfo.merge(infoA, infoB) == result

    where:
    infoA                                                      | infoB                                                      | result
    new PullRequestInfo("branchA", "baseShaA", COMMIT_A, "42") | new PullRequestInfo("branchB", "baseShaB", COMMIT_B, "28") | new PullRequestInfo("branchA", "baseShaA", COMMIT_A, "42")
    new PullRequestInfo(null, null, EMPTY_COMMIT, null)        | new PullRequestInfo("branchB", "baseShaB", COMMIT_B, "28") | new PullRequestInfo("branchB", "baseShaB", COMMIT_B, "28")
    new PullRequestInfo("branchA", null, EMPTY_COMMIT, "42")   | new PullRequestInfo("branchB", "baseShaB", COMMIT_B, null) | new PullRequestInfo("branchA", "baseShaB", COMMIT_B, "42")
    new PullRequestInfo("branchA", null, EMPTY_COMMIT, "42")   | new PullRequestInfo(null, null, EMPTY_COMMIT, null)        | new PullRequestInfo("branchA", null, EMPTY_COMMIT, "42")
  }
}
