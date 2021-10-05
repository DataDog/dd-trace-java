package datadog.trace.bootstrap.instrumentation.ci.git

import datadog.trace.test.util.DDSpecification

class GitUtilsTest extends DDSpecification {

  static janeDoePersonInfo = new PersonInfo("Jane Doe", "jane.doe@email.com")

  def "test split git author into name and email"() {
    when:
    def result = GitUtils.splitAuthorAndEmail(author)

    then:
    result == expectedPersonInfo

    where:
    author | expectedPersonInfo
    null | PersonInfo.NOOP
    ""|PersonInfo.NOOP
    "wrong-data"|PersonInfo.NOOP
    "Jane Doe <jane.doe@email.com>"| janeDoePersonInfo
  }
}
