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

  def "test commit SHA validity of full length (#sha): #expectedResult "() {
    when:
    def result = GitUtils.isValidCommitShaFull(sha)

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

  def "test commit SHA validity (#sha): #expectedResult "() {
    when:
    def result = GitUtils.isValidCommitSha(sha)

    then:
    result == expectedResult

    where:
    sha                                        | expectedResult
    null                                       | false
    ""                                         | false
    "123456"                                   | false
    "123456789"                                | true
    "123456abcdef"                             | true
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

  def "test reference validation"() {
    when:
    def result = GitUtils.isValidRef(ref)

    then:
    result == valid

    where:
    ref                     | valid
    "refs/heads/main"       | true
    "feature/branch-1.0"    | true
    "refs/tags/v1.2.3"      | true
    "origin/main"           | true
    "refs/heads/feature-1"  | true
    "v1.2.3"                | true
    "main"                  | true
    "develop"               | true
    ""                      | false // empty
    "   "                   | false // whitespace only
    null                    | false // null
    "/refs/heads/main"      | false // starts with /
    "refs/heads/main.lock"  | false // ends with .lock
    "refs/heads/ma..in"     | false // contains ..
    "refs/heads/ma in"      | false // contains space
    "refs/heads/ma\tin"     | false // contains tab (control char)
    "refs/heads/ma\u0000in" | false // contains null char
    "refs/heads/ma~in"      | false // contains ~
    "refs/heads/ma^in"      | false // contains ^
    "refs/heads/ma:in"      | false // contains :
    "refs/heads/ma?in"      | false // contains ?
    "refs/heads/ma[in"      | false // contains [
    "refs/heads/ma\\\\in"   | false // contains \\
    "refs/heads/ma|in"      | false // contains | (shell metacharacter)
    "refs/heads/ma&in"      | false // contains & (shell metacharacter)
    "refs/heads/ma`in`"     | false // contains ` (shell metacharacter)
    "refs/heads/ma\$in"     | false // contains $ (shell metacharacter)
    "refs/heads/ma;in"      | false // contains ; (shell metacharacter)
    "refs/heads/main/"      | false // ends with /
    "refs/heads/main."      | false // ends with .
    "refs/heads/@{main"     | false // contains @{
    "@"                     | false // only @
    "refs//heads/main"      | false // contains //
    "refs/./heads/main"     | false // contains /./
    "refs/heads/.hidden"    | false // component starts with .
    "refs/heads/main.lock"  | false // ends with .lock
  }

  def "test path validation"() {
    when:
    def result = GitUtils.isValidPath(path)

    then:
    result == valid

    where:
    path                         | valid
    "path/to/file.txt"           | true
    "folder123/file-name_1"      | true
    "UPPER_CASE/FILE.TXT"        | true
    "simple"                     | true
    "with.dots.txt"              | true
    "with-hyphens"               | true
    "with_underscores"           | true
    "123/numbers/456"            | true
    "mixed.Case-123_Test"        | true
    "./relative/path"            | true
    "multiple/levels/of/nesting" | true
    "/absolute/path"             | true  // absolute paths allowed
    "/home/user/workspace"       | true  // typical CI workspace
    "../parent/path"             | false // path traversal at start
    "path/../other"              | false // path traversal in middle
    "path/to/.."                 | false // path traversal at end
    "../../etc/passwd"           | false // multiple path traversal
    ""                           | false // empty
    "   "                        | false // whitespace only
    "path with spaces"           | false // contains spaces
    "path/with/\ttab"            | false // contains tab
    "path/with/new\nline"        | false // contains newline
    "path/with/special@char"     | false // contains @
    "path/with/hash#"            | false // contains #
    "path/with/pipe|"            | false // contains |
    "path/with/ampersand&"       | false // contains &
    "path/with/dollar\$"         | false // contains $
    "path/with/backtick`"        | false // contains `
    "path/with/semicolon;"       | false // contains semicolon
    "path/with/star*"            | false // contains *
    "path/with/question?"        | false // contains ?
    "path/with/quote\""          | false // contains "
    "path/with/single'"          | false // contains '
    "path/with/backslash\\"      | false // contains \
    "path/with/colon:"           | false // contains :
    "path/with/less<"            | false // contains <
    "path/with/greater>"         | false // contains >
    "path/with/equals="          | false // contains =
    "path/with/plus+"            | false // contains +
    "path/with/percent%"         | false // contains %
    "path/with/curly{}"          | false // contains { or }
    "path/with/square[]"         | false // contains [ or ]
    "path/with/parens()"         | false // contains ( or )
  }
}
