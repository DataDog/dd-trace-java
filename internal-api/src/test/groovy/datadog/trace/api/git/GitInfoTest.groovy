package datadog.trace.api.git


import datadog.trace.test.util.DDSpecification

class GitInfoTest extends DDSpecification {

  def "test builder is returning correct object"() {
    when:
    def gitInfo = new GitInfo("https://some-host/some-user/some-repo.git",
      "some-branch", "some-tag", new CommitInfo(
      "0000000000000000000000000000000000000000",
      new PersonInfo("author-name", "author@email.com", 1614169397000, 1),
      new PersonInfo("committer-name", "committer@email.com", 1614169397000, 1),
      "some commit message\n"))

    then:
    gitInfo.repositoryURL == "https://some-host/some-user/some-repo.git"
    gitInfo.branch == "some-branch"
    gitInfo.tag == "some-tag"
    gitInfo.commit.sha == "0000000000000000000000000000000000000000"
    gitInfo.commit.fullMessage == "some commit message\n"
    gitInfo.commit.author.name == "author-name"
    gitInfo.commit.author.email == "author@email.com"
    gitInfo.commit.author.getIso8601Date() == "2021-02-24T12:23:17.000Z"
    gitInfo.commit.committer.name == "committer-name"
    gitInfo.commit.committer.email == "committer@email.com"
    gitInfo.commit.committer.getIso8601Date() == "2021-02-24T12:23:17.000Z"
  }
}
