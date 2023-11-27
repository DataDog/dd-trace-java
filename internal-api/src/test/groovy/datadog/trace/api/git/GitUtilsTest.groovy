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

  def "test sensitive info filtering in URL: #url"() {
    when:
    def result = GitUtils.filterSensitiveInfo(url)

    then:
    result == expectedResult

    where:
    url                                                       | expectedResult
    null                                                      | null
    ""                                                        | null
    "http://host.com/path"                                    | "http://host.com/path"
    "https://host.com/path"                                   | "https://host.com/path"
    "ssh://host.com/path"                                     | "ssh://host.com/path"
    "http://user@host.com/path"                               | "http://host.com/path"
    "https://user@host.com/path"                              | "https://host.com/path"
    "ssh://user@host.com/path"                                | "ssh://host.com/path"
    "http://user:password@host.com/path"                      | "http://host.com/path"
    "https://user:password@host.com/path"                     | "https://host.com/path"
    "ssh://user:password@host.com/path"                       | "ssh://host.com/path"
    "ssh://host.com:2222/path"                                | "ssh://host.com:2222/path"
    "https://example.com/user/repo@version.git"               | "https://example.com/user/repo@version.git"
    "https://user@example.com/user/repo@version.git"          | "https://example.com/user/repo@version.git"
    "https://user:password@example.com/user/repo@version.git" | "https://example.com/user/repo@version.git"
    "git@example.com:repo.git"                                | "git@example.com:repo.git"
  }
}
