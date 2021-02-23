package datadog.trace.bootstrap.instrumentation.ci.git

import datadog.trace.test.util.DDSpecification

import java.nio.file.Paths

class LocalFSGitInfoExtractorTest extends DDSpecification {

  static gitInfoNoCommits
  static gitInfoOneCommit
  static gitInfoOneCommitNoRef
  static gitInfoOneTag

  static {
    gitInfoNoCommits = GitInfo.builder()
      .repositoryURL("https://some-host/some-user/some-repo.git")
      .branch("master")
      .tag(null)
      .commit(CommitInfo.NOOP)
      .build()

    gitInfoOneCommit = GitInfo.builder()
      .repositoryURL("https://some-host/some-user/some-repo.git")
      .branch("master")
      .tag(null)
      .commit(CommitInfo.builder()
        .sha("0797c248e019314fc1d91a483e859b32f4509953")
        .author(PersonInfo.builder().name("John Doe").email("john@doe.com").when(1613137668000L).tzOffset(60).build())
        .committer(PersonInfo.builder().name("Jane Doe").email("jane@doe.com").when(1613137724000L).tzOffset(60).build())
        .fullMessage("This is a commit message\n")
        .build())
      .build()

    gitInfoOneCommitNoRef = GitInfo.builder()
      .repositoryURL("https://some-host/some-user/some-repo.git")
      .branch(null)
      .tag(null)
      .commit(CommitInfo.builder()
        .sha("0797c248e019314fc1d91a483e859b32f4509953")
        .author(PersonInfo.builder().name("John Doe").email("john@doe.com").when(1613137668000L).tzOffset(60).build())
        .committer(PersonInfo.builder().name("Jane Doe").email("jane@doe.com").when(1613137724000L).tzOffset(60).build())
        .fullMessage("This is a commit message\n")
        .build())
      .build()

    gitInfoOneTag = GitInfo.builder()
      .repositoryURL("https://some-host/some-user/some-repo.git")
      .branch(null)
      .tag("1.0")
      .commit(CommitInfo.builder()
        .sha("643f93d12768105fa9bd1a548c767c4ea11f75d7")
        .author(PersonInfo.builder().name("John Doe").email("john@doe.com").when(1613138422000L).tzOffset(60).build())
        .committer(PersonInfo.builder().name("Jane Doe").email("jane@doe.com").when(1613138422000L).tzOffset(60).build())
        .fullMessage("This is a commit message\n")
        .build())
      .build()
  }

  def "test git info extraction for local fs"() {
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
    resolve("ci/git/no_commits/git")           | gitInfoNoCommits
    resolve("ci/git/with_commits/git")         | gitInfoOneCommit
    resolve("ci/git/with_commits_no_refs/git") | gitInfoOneCommitNoRef
    resolve("ci/git/with_tag/git")             | gitInfoOneTag
  }

  def "resolve"(workspace) {
    def resolvedWS = Paths.get(getClass().getClassLoader().getResource(workspace).toURI()).toFile().getAbsolutePath()
    println(resolvedWS)
    return resolvedWS
  }
}
