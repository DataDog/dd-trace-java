package datadog.trace.bootstrap.instrumentation.ci.git


import datadog.trace.test.util.DDSpecification

import java.nio.file.Paths

class LocalFSGitInfoExtractorTest extends DDSpecification {

  static gitInfoNoCommits
  static gitInfoOneCommit
  static gitInfoOneCommitNoRef
  static gitInfoOneTag
  static gitInfoPack

  static {
    gitInfoNoCommits = new GitInfo("https://some-host/some-user/some-repo.git",
      "master", null, CommitInfo.NOOP)

    gitInfoOneCommit = new GitInfo("https://some-host/some-user/some-repo.git",
      "master", null, new CommitInfo(
      "0797c248e019314fc1d91a483e859b32f4509953",
      new PersonInfo("John Doe", "john@doe.com", 1613137668000L, 60),
      new PersonInfo("Jane Doe", "jane@doe.com", 1613137724000L, 60),
      "This is a commit message\n"))

    gitInfoOneCommitNoRef = new GitInfo("https://some-host/some-user/some-repo.git",
      null, null, new CommitInfo(
      "0797c248e019314fc1d91a483e859b32f4509953",
      new PersonInfo("John Doe", "john@doe.com", 1613137668000L, 60),
      new PersonInfo("Jane Doe", "jane@doe.com", 1613137724000L, 60),
      "This is a commit message\n"))

    gitInfoOneTag = new GitInfo("https://some-host/some-user/some-repo.git",
      null, "1.0", new CommitInfo("643f93d12768105fa9bd1a548c767c4ea11f75d7",
      new PersonInfo("John Doe", "john@doe.com", 1613138422000L, 60),
      new PersonInfo("Jane Doe", "jane@doe.com", 1613138422000L, 60),
      "This is a commit message\n"))

    gitInfoPack = new GitInfo("git@github.com:DataDog/dd-trace-dotnet.git", "master", null,
      new CommitInfo("5b6f3a6dab5972d73a56dff737bd08d995255c08",
      new PersonInfo("Tony Redondo", "tony.redondo@datadoghq.com", 1614364333000L, 60),
      new PersonInfo("GitHub", "noreply@github.com", 1614364333000L, 60),
      "Adding Git information to test spans (#1242)\n\n* Initial basic GitInfo implementation.\r\n\r\n* Adds Author, Committer and Message git parser.\r\n\r\n* Changes based on the review.")
      )
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
    resolve("ci/git/with_pack/git")            | gitInfoPack
  }

  def "test extract correct git info with complex commit object"() {
    setup:
    def commitBytes = ("tree e3a1035abd2b319bb01e57d69b0ba6cab289297e\n" +
      "parent 54e895b87c0768d2317a2b17062e3ad9f76a8105\n" +
      "author A U Thor <author@xample.com> 1528968566\n" +
      "committer A U Thor <committer@xample.com> 1528968566\n" +
      "gpgsig -----BEGIN PGP SIGNATURE-----\n" +
      " \n" +
      " wsBcBAABCAAQBQJbGB4pCRBK7hj4Ov3rIwAAdHIIAENrvz23867ZgqrmyPemBEZP\n" +
      " U24B1Tlq/DWvce2buaxmbNQngKZ0pv2s8VMc11916WfTIC9EKvioatmpjduWvhqj\n" +
      " znQTFyiMor30pyYsfrqFuQZvqBW01o8GEWqLg8zjf9Rf0R3LlOEw86aT8CdHRlm6\n" +
      " wlb22xb8qoX4RB+LYfz7MhK5F+yLOPXZdJnAVbuyoMGRnDpwdzjL5Hj671+XJxN5\n" +
      " SasRdhxkkfw/ZnHxaKEc4juMz8Nziz27elRwhOQqlTYoXNJnsV//wy5Losd7aKi1\n" +
      " xXXyUpndEOmT0CIcKHrN/kbYoVL28OJaxoBuva3WYQaRrzEe3X02NMxZe9gkSqA=\n" +
      " =TClh\n" +
      " -----END PGP SIGNATURE-----\n" +
      "some other header\n\n" +
      "commit message").bytes
    def sut = new LocalFSGitInfoExtractor()

    when:
    def author = sut.getAuthor(commitBytes)
    def committer = sut.getCommitter(commitBytes)
    def fullMessage = sut.getFullMessage(commitBytes)

    then:
    author == new PersonInfo("A U Thor", "author@xample.com", 0L, 0)
    committer == new PersonInfo("A U Thor", "committer@xample.com", 0L, 0)
    fullMessage == "commit message"
  }

  def "test correct behaviour if commit info is malformed"() {
    setup:
    def malformedCommitBytes = "".bytes
    def sut = new LocalFSGitInfoExtractor()

    when:
    def author = sut.getAuthor(malformedCommitBytes)
    def committer = sut.getCommitter(malformedCommitBytes)
    def fullMessage = sut.getFullMessage(malformedCommitBytes)

    then:
    author == PersonInfo.NOOP
    committer == PersonInfo.NOOP
    fullMessage == null
  }

  def "test repository url with different remotes"() {
    setup:
    def sut = new LocalFSGitInfoExtractor()

    when:
    def gitInfo = sut.headCommit(gitFolder)

    then:
    gitInfo.repositoryURL == expectedRepositoryURL

    where:
    gitFolder                                       | expectedRepositoryURL
    resolve("ci/git/with_repo_config")              | "https://some-host/user/repository.git"
    resolve("ci/git/with_repo_config_other_origin") | "https://some-host/user/other_repository.git"
  }

  def "resolve"(workspace) {
    def resolvedWS = Paths.get(getClass().getClassLoader().getResource(workspace).toURI()).toFile().getAbsolutePath()
    println(resolvedWS)
    return resolvedWS
  }
}
