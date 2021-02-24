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
    author == PersonInfo.builder().name("A U Thor").email("author@xample.com").when(0L).tzOffset(0).build()
    committer == PersonInfo.builder().name("A U Thor").email("committer@xample.com").when(0L).tzOffset(0).build()
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

  def "resolve"(workspace) {
    def resolvedWS = Paths.get(getClass().getClassLoader().getResource(workspace).toURI()).toFile().getAbsolutePath()
    println(resolvedWS)
    return resolvedWS
  }
}
