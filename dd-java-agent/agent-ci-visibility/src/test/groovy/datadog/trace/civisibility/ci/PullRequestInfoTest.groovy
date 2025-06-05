package datadog.trace.civisibility.ci

import spock.lang.Specification

class PullRequestInfoTest extends Specification {
  def "test merging of informations"() {
    PullRequestInfo.merge(infoA, infoB) == result

    where:
    infoA                                      | infoB                                    | result
    new PullRequestInfo("branchA", "a", "a")   | new PullRequestInfo("branchB", "b", "b") | new PullRequestInfo("branchA", "a", "a")
    new PullRequestInfo(null, null, null)      | new PullRequestInfo("branchB", "b", "b") | new PullRequestInfo("branchB", "b", "b")
    new PullRequestInfo("branchA", null, null) | new PullRequestInfo("branchB", "b", "b") | new PullRequestInfo("branchA", "b", "b")
    new PullRequestInfo("branchA", null, null) | new PullRequestInfo(null, null, null)    | new PullRequestInfo("branchA", null, null)
  }
}
