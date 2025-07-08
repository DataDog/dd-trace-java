package datadog.trace.civisibility.ci

import spock.lang.Specification

class PullRequestInfoTest extends Specification {
  def "test merging of informations"() {
    PullRequestInfo.merge(infoA, infoB) == result

    where:
    infoA                                            | infoB                                          | result
    new PullRequestInfo("branchA", "a", "a", "42")   | new PullRequestInfo("branchB", "b", "b", "28") | new PullRequestInfo("branchA", "a", "a", "42")
    new PullRequestInfo(null, null, null, null)      | new PullRequestInfo("branchB", "b", "b", "42") | new PullRequestInfo("branchB", "b", "b", "42")
    new PullRequestInfo("branchA", null, null, "42") | new PullRequestInfo("branchB", "b", "b", null) | new PullRequestInfo("branchA", "b", "b", "42")
    new PullRequestInfo("branchA", null, null, "42") | new PullRequestInfo(null, null, null, null)    | new PullRequestInfo("branchA", null, null, null)
  }
}
