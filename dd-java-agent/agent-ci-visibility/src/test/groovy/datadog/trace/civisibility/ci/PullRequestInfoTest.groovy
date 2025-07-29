package datadog.trace.civisibility.ci

import datadog.trace.api.git.CommitInfo
import datadog.trace.api.git.PersonInfo
import spock.lang.Specification

class PullRequestInfoTest extends Specification {
  private static final PersonInfo PERSON_A = new PersonInfo("nameA", "emailA", "dateA")
  private static final PersonInfo PERSON_B = new PersonInfo("nameB", "emailB", "dateB")
  private static final PersonInfo EMPTY_PERSON = new PersonInfo(null, null, null)
  private static final String SHA_A = "shaA"
  private static final String SHA_B = "shaB"
  private static final CommitInfo COMMIT_A = new CommitInfo(SHA_A, PERSON_A, PERSON_A, "msgA")
  private static final CommitInfo COMMIT_B = new CommitInfo(SHA_B, PERSON_B, PERSON_B, "msgB")
  private static final CommitInfo EMPTY_COMMIT = new CommitInfo(null, EMPTY_PERSON, EMPTY_PERSON, null)

  def "test isEmpty"() {
    expect:
    info.isEmpty() == empty

    where:
    info                                                            | empty
    new PullRequestInfo(null, null, null, EMPTY_COMMIT, null)       | true
    new PullRequestInfo("", "", null, EMPTY_COMMIT, "")             | true
    new PullRequestInfo(null, "", null, COMMIT_A, "42")             | false
    new PullRequestInfo("branch", "baseSha", SHA_A, COMMIT_A, "42") | false
  }

  def "test isComplete"() {
    expect:
    info.isComplete() == empty

    where:
    info                                                            | empty
    new PullRequestInfo(null, null, null, EMPTY_COMMIT, null)       | false
    new PullRequestInfo("", "", null, EMPTY_COMMIT, "")             | false
    new PullRequestInfo("branch", "", null, COMMIT_A, "42")         | false
    new PullRequestInfo("branch", "baseSha", SHA_A, COMMIT_A, "42") | true
  }

  def "test info coalesce"() {
    expect:
    PullRequestInfo.coalesce(infoA, infoB) == result

    where:
    infoA                                                             | infoB                                                             | result
    new PullRequestInfo("branchA", "baseShaA", SHA_A, COMMIT_A, "42") | new PullRequestInfo("branchB", "baseShaB", SHA_B, COMMIT_B, "28") | new PullRequestInfo("branchA", "baseShaA", SHA_A, COMMIT_A, "42")
    new PullRequestInfo(null, null, null, EMPTY_COMMIT, null)         | new PullRequestInfo("branchB", "baseShaB", SHA_B, COMMIT_B, "28") | new PullRequestInfo("branchB", "baseShaB", SHA_B, COMMIT_B, "28")
    new PullRequestInfo("branchA", null, SHA_A, EMPTY_COMMIT, "42")   | new PullRequestInfo("branchB", "baseShaB", null, COMMIT_B, null)  | new PullRequestInfo("branchA", "baseShaB", SHA_A, COMMIT_B, "42")
    new PullRequestInfo("branchA", null, null, EMPTY_COMMIT, "42")    | new PullRequestInfo(null, null, null, EMPTY_COMMIT, null)         | new PullRequestInfo("branchA", null, null, EMPTY_COMMIT, "42")
  }
}
