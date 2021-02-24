package datadog.trace.bootstrap.instrumentation.ci.git

import datadog.trace.test.util.DDSpecification

class GitInfoTest extends DDSpecification {

  def "test builder is returning correct object"() {
    when:
    def gitInfo = GitInfo.builder()
      .repositoryURL("https://some-host/some-user/some-repo.git")
      .branch("some-branch")
      .tag("some-tag")
      .commit(CommitInfo.builder()
        .sha("0000000000000000000000000000000000000000")
        .fullMessage("some commit message\n")
        .author(PersonInfo.builder()
          .name("author-name")
          .email("author@email.com")
          .when(1614169397000)
          .tzOffset(1)
          .build())
        .committer(PersonInfo.builder()
          .name("committer-name")
          .email("committer@email.com")
          .when(1614169397000)
          .tzOffset(1)
          .build())
        .build())
      .build()

    then:
    gitInfo.repositoryURL == "https://some-host/some-user/some-repo.git"
    gitInfo.branch == "some-branch"
    gitInfo.tag == "some-tag"
    gitInfo.commit.sha == "0000000000000000000000000000000000000000"
    gitInfo.commit.fullMessage == "some commit message\n"
    gitInfo.commit.author.name == "author-name"
    gitInfo.commit.author.email == "author@email.com"
    gitInfo.commit.author.getISO8601Date() == "2021-02-24T12:23:17.000Z"
    gitInfo.commit.committer.name == "committer-name"
    gitInfo.commit.committer.email == "committer@email.com"
    gitInfo.commit.committer.getISO8601Date() == "2021-02-24T12:23:17.000Z"
  }
}
