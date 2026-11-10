package datadog.trace.api.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

class GitUtilsTest {

  @TableTest({
    "scenario        | revision        | invalid",
    "all refs option | --all           | true   ",
    "output option   | --output=result | true   ",
    "head            | HEAD            | false  ",
    "branch          | refs/heads/main | false  ",
    "short SHA       | 123abcd         | false  ",
    "missing         |                 | true   ",
    "empty           | ''              | true   "
  })
  void testCommitReferenceRejectsOptions(String revision, boolean invalid) {
    assertEquals(invalid, GitUtils.isNotValidCommit(revision));
  }

  private static final PersonInfo JANE_DOE_PERSON_INFO =
      new PersonInfo("Jane Doe", "jane.doe@email.com");

  @ParameterizedTest(name = "{0}")
  @MethodSource("testSplitGitAuthorIntoNameAndEmailArguments")
  void testSplitGitAuthorIntoNameAndEmail(
      String description, String author, PersonInfo expectedPersonInfo) {
    PersonInfo result = GitUtils.splitAuthorAndEmail(author);

    assertEquals(expectedPersonInfo, result);
  }

  static Stream<Arguments> testSplitGitAuthorIntoNameAndEmailArguments() {
    return Stream.of(
        arguments("null author", null, PersonInfo.NOOP),
        arguments("empty author", "", PersonInfo.NOOP),
        arguments("wrong data", "wrong-data", PersonInfo.NOOP),
        arguments("name and email", "Jane Doe <jane.doe@email.com>", JANE_DOE_PERSON_INFO));
  }

  @TableTest({
    "scenario      | sha                                      | expectedResult",
    "null          |                                          | false         ",
    "empty         | ''                                       | false         ",
    "too short     | 123456789                                | false         ",
    "digits        | 1234567890123456789012345678901234567890 | true          ",
    "lowercase hex | 1234567890123456789012345678901234abcdef | true          ",
    "uppercase hex | 1234567890123456789012345678901234ABCDEF | true          ",
    "non-hex char  | 1234567890123456789012345678901234ABCDEX | false         "
  })
  void testCommitShaValidityOfFullLength(String sha, boolean expectedResult) {
    assertEquals(expectedResult, GitUtils.isValidCommitShaFull(sha));
  }

  @TableTest({
    "scenario           | sha                                      | expectedResult",
    "null               |                                          | false         ",
    "empty              | ''                                       | false         ",
    "too short          | 123456                                   | false         ",
    "short digits       | 123456789                                | true          ",
    "short hex          | 123456abcdef                             | true          ",
    "full lowercase hex | 1234567890123456789012345678901234abcdef | true          ",
    "full uppercase hex | 1234567890123456789012345678901234ABCDEF | true          ",
    "non-hex char       | 1234567890123456789012345678901234ABCDEX | false         "
  })
  void testCommitShaValidity(String sha, boolean expectedResult) {
    assertEquals(expectedResult, GitUtils.isValidCommitSha(sha));
  }

  @TableTest({
    "scenario                           | url                                                       | expectedResult                             ",
    "null                               |                                                           |                                            ",
    "empty                              | ''                                                        |                                            ",
    "http                               | 'http://host.com/path'                                    | 'http://host.com/path'                     ",
    "https                              | 'https://host.com/path'                                   | 'https://host.com/path'                    ",
    "ssh                                | 'ssh://host.com/path'                                     | 'ssh://host.com/path'                      ",
    "http with user                     | 'http://user@host.com/path'                               | 'http://host.com/path'                     ",
    "https with user                    | 'https://user@host.com/path'                              | 'https://host.com/path'                    ",
    "ssh with user                      | 'ssh://user@host.com/path'                                | 'ssh://host.com/path'                      ",
    "http with user and password        | 'http://user:password@host.com/path'                      | 'http://host.com/path'                     ",
    "https with user and password       | 'https://user:password@host.com/path'                     | 'https://host.com/path'                    ",
    "ssh with user and password         | 'ssh://user:password@host.com/path'                       | 'ssh://host.com/path'                      ",
    "ssh with port                      | 'ssh://host.com:2222/path'                                | 'ssh://host.com:2222/path'                 ",
    "at sign in path                    | 'https://example.com/user/repo@version.git'               | 'https://example.com/user/repo@version.git'",
    "user and at sign in path           | 'https://user@example.com/user/repo@version.git'          | 'https://example.com/user/repo@version.git'",
    "user, password and at sign in path | 'https://user:password@example.com/user/repo@version.git' | 'https://example.com/user/repo@version.git'",
    "scp-like syntax                    | 'git@example.com:repo.git'                                | 'git@example.com:repo.git'                 "
  })
  void testSensitiveInfoFilteringInUrl(String url, String expectedResult) {
    assertEquals(expectedResult, GitUtils.filterSensitiveInfo(url));
  }

  @TableTest({
    "scenario            | url                                                                 | expectedResult                                  ",
    "ftps credentials    | 'ftps://user:password@host.com/repo.git'                            | 'ftps://host.com/repo.git'                      ",
    "git custom scheme   | 'git+ssh://user:password@host.com:2222/repo.git'                    | 'git+ssh://host.com:2222/repo.git'              ",
    "uppercase scheme    | 'HTTPS://user:password@host.com/repo.git'                           | 'HTTPS://host.com/repo.git'                     ",
    "encoded components  | 'ftps://user%40name:p%3Ass@host.com/repo%2Fpart.git?q=%40#revision' | 'ftps://host.com/repo%2Fpart.git?q=%40#revision'",
    "network path        | '//user:password@host.com/repo.git'                                 | '//host.com/repo.git'                           ",
    "IPv6 URI            | 'ftps://user:password@[2001:db8::1]:2121/repo.git'                  | 'ftps://[2001:db8::1]:2121/repo.git'            ",
    "IPv6 scp            | 'git@[2001:db8::1]:repo.git'                                        | 'git@[2001:db8::1]:repo.git'                    ",
    "plain CI value      | 'integration test job'                                              | 'integration test job'                          ",
    "CI value with colon | 'build: integration tests'                                          | 'build: integration tests'                      ",
    "malformed path      | 'ftps://user:password@host.com/repo name.git'                       |                                                 ",
    "malformed authority | 'ftps://user:password@@host.com/repo.git'                           |                                                 ",
    "missing host        | 'ftps://user:password@/repo.git'                                    |                                                 ",
    "opaque credentials  | 'ftps:user:password@host.com/repo.git'                              |                                                 "
  })
  void testCredentialsAreFilteredForAllUriSchemes(String url, String expectedResult) {
    assertEquals(expectedResult, GitUtils.filterSensitiveInfo(url));
  }

  @TableTest({
    "scenario          | url                                                | expectedResult                                    ",
    "SSH alias         | 'ssh://git@git_alias/team/repo.git'                | 'ssh://git_alias/team/repo.git'                   ",
    "alias credentials | 'ssh://user:password@git_alias:2222/team/repo.git' | 'ssh://git_alias:2222/team/repo.git'              ",
    "custom scheme     | 'git+ssh://git@git_alias/team/repo.git'            | 'git+ssh://git_alias/team/repo.git'               ",
    "SCP alias path    | 'git_alias:repos/@team/project.git'                | 'git_alias:repos/@team/project.git'               ",
    "SCP host path     | 'host.com:repos/team@version/project.git'          | 'host.com:repos/team@version/project.git'         ",
    "SCP leading at    | 'git_alias:@team/project.git'                      | 'git_alias:@team/project.git'                     ",
    "SCP path at       | 'git_alias:project@version.git'                    | 'git_alias:project@version.git'                   ",
    "SCP IPv6 path     | '[2001:db8::1]:repos/@team/project.git'            | '[2001:db8::1]:repos/@team/project.git'           ",
    "SCP path colons   | 'git_alias:repos/release:1@stable/project.git'     | 'git_alias:repos/release:1@stable/project.git'    ",
    "SCP IPv6 colons   | '[2001:db8::1]:repos/release:1@stable/project.git' | '[2001:db8::1]:repos/release:1@stable/project.git'",
    "invalid port      | 'ssh://user:password@git_alias:invalid/repo.git'   |                                                   "
  })
  void testHostAliasesKeepSanitizedRepositoryIdentity(String url, String expectedResult) {
    assertEquals(expectedResult, GitUtils.filterSensitiveInfo(url));
  }

  @TableTest({
    "scenario                           | ref                     | valid",
    "full branch ref                    | refs/heads/main         | true ",
    "branch with slash and dots         | feature/branch-1.0      | true ",
    "tag ref                            | refs/tags/v1.2.3        | true ",
    "remote branch                      | origin/main             | true ",
    "branch with dash                   | refs/heads/feature-1    | true ",
    "short tag                          | v1.2.3                  | true ",
    "main                               | main                    | true ",
    "develop                            | develop                 | true ",
    "option                             | --all                   | false",
    "empty                              | ''                      | false",
    "whitespace only                    | '   '                   | false",
    "null                               |                         | false",
    "starts with /                      | /refs/heads/main        | false",
    "ends with .lock                    | refs/heads/main.lock    | false",
    "contains ..                        | refs/heads/ma..in       | false",
    "contains space                     | 'refs/heads/ma in'      | false",
    "contains ~                         | 'refs/heads/ma~in'      | false",
    "contains ^                         | 'refs/heads/ma^in'      | false",
    "contains :                         | 'refs/heads/ma:in'      | false",
    "contains ?                         | 'refs/heads/ma?in'      | false",
    "contains [                         | 'refs/heads/ma[in'      | false",
    "'contains | (shell metacharacter)' | 'refs/heads/ma|in'      | false",
    "contains & (shell metacharacter)   | 'refs/heads/ma&in'      | false",
    "contains ` (shell metacharacter)   | 'refs/heads/ma`in`'     | false",
    "contains $ (shell metacharacter)   | 'refs/heads/ma$in'      | false",
    "contains ; (shell metacharacter)   | 'refs/heads/ma;in'      | false",
    "ends with /                        | refs/heads/main/        | false",
    "ends with .                        | refs/heads/main.        | false",
    "contains @{                        | 'refs/heads/@{main'     | false",
    "only @                             | '@'                     | false",
    "contains //                        | refs//heads/main        | false",
    "contains /./                       | refs/./heads/main       | false",
    "component starts with .            | refs/heads/.hidden      | false",
    "ends with .lock again              | refs/heads/main.lock    | false",
    "contains tab (control char)        | 'refs/heads/ma\tin'     | false",
    "contains null char                 | 'refs/heads/ma\u0000in' | false",
    "contains \\\\                      | 'refs/heads/ma\\\\in'   | false"
  })
  void testReferenceValidation(String ref, boolean valid) {
    assertEquals(valid, GitUtils.isValidRef(ref));
  }

  @TableTest({
    "scenario                    | path                       | valid",
    "relative file               | path/to/file.txt           | true ",
    "digits, dash and underscore | folder123/file-name_1      | true ",
    "upper case                  | UPPER_CASE/FILE.TXT        | true ",
    "simple                      | simple                     | true ",
    "with dots                   | with.dots.txt              | true ",
    "with hyphens                | with-hyphens               | true ",
    "with underscores            | with_underscores           | true ",
    "with numbers                | 123/numbers/456            | true ",
    "mixed case                  | mixed.Case-123_Test        | true ",
    "dot relative path           | ./relative/path            | true ",
    "multiple levels             | multiple/levels/of/nesting | true ",
    "absolute paths allowed      | /absolute/path             | true ",
    "typical CI workspace        | /home/user/workspace       | true ",
    "path traversal at start     | ../parent/path             | false",
    "path traversal in middle    | path/../other              | false",
    "path traversal at end       | path/to/..                 | false",
    "multiple path traversal     | ../../etc/passwd           | false",
    "empty                       | ''                         | false",
    "whitespace only             | '   '                      | false",
    "contains spaces             | 'path with spaces'         | false",
    "contains @                  | 'path/with/special@char'   | false",
    "contains #                  | 'path/with/hash#'          | false",
    "'contains |'                | 'path/with/pipe|'          | false",
    "contains &                  | 'path/with/ampersand&'     | false",
    "contains $                  | 'path/with/dollar$'        | false",
    "contains `                  | 'path/with/backtick`'      | false",
    "contains semicolon          | 'path/with/semicolon;'     | false",
    "contains *                  | 'path/with/star*'          | false",
    "contains ?                  | 'path/with/question?'      | false",
    "contains double quote       | 'path/with/quote\"'        | false",
    "contains single quote       | \"path/with/single'\"      | false",
    "contains :                  | 'path/with/colon:'         | false",
    "contains <                  | 'path/with/less<'          | false",
    "contains >                  | 'path/with/greater>'       | false",
    "contains =                  | 'path/with/equals='        | false",
    "contains +                  | 'path/with/plus+'          | false",
    "contains %                  | 'path/with/percent%'       | false",
    "contains { or }             | 'path/with/curly{}'        | false",
    "contains [ or ]             | 'path/with/square[]'       | false",
    "contains ( or )             | 'path/with/parens()'       | false",
    "contains tab                | 'path/with/\ttab'          | false",
    "contains \\                 | 'path/with/backslash\\'    | false"
  })
  @MethodSource("testPathValidationArguments")
  void testPathValidation(String path, boolean valid) {
    assertEquals(valid, GitUtils.isValidPath(path));
  }

  static Stream<Arguments> testPathValidationArguments() {
    // a newline cannot be put in a @TableTest cell as it is the row separator
    return Stream.of(arguments(named("contains newline", "path/with/new\nline"), false));
  }
}
