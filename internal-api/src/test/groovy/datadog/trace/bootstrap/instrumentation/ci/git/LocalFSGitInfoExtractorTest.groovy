package datadog.trace.bootstrap.instrumentation.ci.git

import datadog.trace.test.util.DDSpecification

import java.nio.file.Paths

class LocalFSGitInfoExtractorTest extends DDSpecification {

  static gitInfoOneCommit
  static gitInfoOneTag

  static {
    gitInfoOneCommit = GitInfo.builder()
      .commit(CommitInfo.builder()
        .author(PersonInfo.builder().name("John Doe").email("john@doe.com").when(1613137668000L).tzOffset(60).build())
        .committer(PersonInfo.builder().name("Jane Doe").email("jane@doe.com").when(1613137724000L).tzOffset(60).build())
        .fullMessage("This is a commit message\n")
        .build())
      .build()

    gitInfoOneTag = GitInfo.builder()
      .commit(CommitInfo.builder()
        .author(PersonInfo.builder().name("John Doe").email("john@doe.com").when(1613138422000L).tzOffset(60).build())
        .committer(PersonInfo.builder().name("Jane Doe").email("jane@doe.com").when(1613138422000L).tzOffset(60).build())
        .fullMessage("This is a commit message\n")
        .build())
      .build()
  }

  def "test"() {
    setup:
    def sut = new LocalFSGitInfoExtractor()

    when:
    def gitInfo = sut.headCommit(gitFolder)

    then:
    gitInfo == expectedGitInfo

    where:
    gitFolder                                  | expectedGitInfo
    null                                       | GitInfo.NOOP
    ""                                         | GitInfo.NOOP
    resolve("ci/git/no_commits/git")           | GitInfo.NOOP
    resolve("ci/git/with_commits/git")         | gitInfoOneCommit
    resolve("ci/git/with_commits_no_refs/git") | gitInfoOneCommit
    resolve("ci/git/with_tag/git")             | gitInfoOneTag
  }

  def "resolve"(workspace) {
    def resolvedWS = Paths.get(getClass().getClassLoader().getResource(workspace).toURI()).toFile().getAbsolutePath()
    println(resolvedWS)
    return resolvedWS
  }
}
