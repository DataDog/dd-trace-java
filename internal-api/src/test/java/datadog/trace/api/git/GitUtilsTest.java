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
    "scenario                           | ref                     | valid",
    "full branch ref                    | refs/heads/main         | true ",
    "branch with slash and dots         | feature/branch-1.0      | true ",
    "tag ref                            | refs/tags/v1.2.3        | true ",
    "remote branch                      | origin/main             | true ",
    "branch with dash                   | refs/heads/feature-1    | true ",
    "short tag                          | v1.2.3                  | true ",
    "main                               | main                    | true ",
    "develop                            | develop                 | true ",
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
