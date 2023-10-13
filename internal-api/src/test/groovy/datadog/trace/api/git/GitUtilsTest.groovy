package datadog.trace.api.git


import spock.lang.Specification

class GitUtilsTest extends Specification {

  static janeDoePersonInfo = new PersonInfo("Jane Doe", "jane.doe@email.com")

  def "test split git author into name and email"() {
    when:
    def result = GitUtils.splitAuthorAndEmail(author)

    then:
    result == expectedPersonInfo

    where:
    author                          | expectedPersonInfo
    null                            | PersonInfo.NOOP
    ""                              | PersonInfo.NOOP
    "wrong-data"                    | PersonInfo.NOOP
    "Jane Doe <jane.doe@email.com>" | janeDoePersonInfo
  }

  def "test commit SHA validity (#sha): #expectedResult "() {
    when:
    def result = GitUtils.isValidCommitSha(sha)

    then:
    result == expectedResult

    where:
    sha                                        | expectedResult
    null                                       | false
    ""                                         | false
    "123456789"                                | false
    "1234567890123456789012345678901234567890" | true
    "1234567890123456789012345678901234abcdef" | true
    "1234567890123456789012345678901234ABCDEF" | true
    "1234567890123456789012345678901234ABCDEX" | false
  }
}
