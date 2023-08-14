package datadog.trace.api.git

import spock.lang.Specification

class GitInfoProviderTest extends Specification {

  private static final String REPO_PATH = "/repo/path"

  def "test delegates to GitInfoBuilder"() {
    setup:
    def gitInfoBuilder = givenABuilderReturning(
      new GitInfo("repoUrl", "branch", "tag", new CommitInfo("sha"))
      )

    def gitInfoProvider = new GitInfoProvider()
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilder)

    when:
    def actualGitInfo = gitInfoProvider.getGitInfo(REPO_PATH)

    then:
    actualGitInfo.repositoryURL == "repoUrl"
    actualGitInfo.branch == "branch"
    actualGitInfo.tag == "tag"
    actualGitInfo.commit.sha == "sha"
  }

  def "test falls back to the second GitInfoBuilder"() {
    setup:
    def gitInfoBuilderA = givenABuilderReturning(
      new GitInfo("repoUrl", null, null, new CommitInfo(null))
      )

    def gitInfoBuilderB = givenABuilderReturning(
      new GitInfo(null, "branch", "tag", new CommitInfo("sha")), 2
      )

    def gitInfoProvider = new GitInfoProvider()
    // registering provider with higher order first, to check that the registration logic will do proper reordering after the second registration
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderB)
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderA)

    when:
    def actualGitInfo = gitInfoProvider.getGitInfo(REPO_PATH)

    then:
    actualGitInfo.repositoryURL == "repoUrl"
    actualGitInfo.branch == "branch"
    actualGitInfo.tag == "tag"
    actualGitInfo.commit.sha == "sha"
  }

  def "test falls back to the second GitInfoBuilder for empty strings"() {
    setup:
    def gitInfoBuilderA = givenABuilderReturning(
      new GitInfo("repoUrl", "", "", new CommitInfo(""))
      )

    def gitInfoBuilderB = givenABuilderReturning(
      new GitInfo(null, "branch", "tag", new CommitInfo("sha"))
      )

    def gitInfoProvider = new GitInfoProvider()
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderA)
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderB)

    when:
    def actualGitInfo = gitInfoProvider.getGitInfo(REPO_PATH)

    then:
    actualGitInfo.repositoryURL == "repoUrl"
    actualGitInfo.branch == "branch"
    actualGitInfo.tag == "tag"
    actualGitInfo.commit.sha == "sha"
  }

  def "test falls back to the second GitInfoBuilder for blank strings"() {
    setup:
    def gitInfoBuilderA = givenABuilderReturning(
      new GitInfo("repoUrl", " ", " ", new CommitInfo(" "))
      )

    def gitInfoBuilderB = givenABuilderReturning(
      new GitInfo(null, "branch", "tag", new CommitInfo("sha"))
      )

    def gitInfoProvider = new GitInfoProvider()
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderA)
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderB)

    when:
    def actualGitInfo = gitInfoProvider.getGitInfo(REPO_PATH)

    then:
    actualGitInfo.repositoryURL == "repoUrl"
    actualGitInfo.branch == "branch"
    actualGitInfo.tag == "tag"
    actualGitInfo.commit.sha == "sha"
  }

  def "test falls back to the second GitInfoBuilder for commit info"() {
    setup:
    def gitInfoBuilderA = givenABuilderReturning(
      new GitInfo("repoUrl", null, null,
      new CommitInfo("sha",
      PersonInfo.NOOP,
      PersonInfo.NOOP,
      null)))

    def gitInfoBuilderB = givenABuilderReturning(
      new GitInfo("repoUrl", null, null,
      new CommitInfo("sha",
      new PersonInfo("author name", "author email", "author date"),
      new PersonInfo("committer name", "committer email", "committer date"),
      "message")))

    def gitInfoProvider = new GitInfoProvider()
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderA)
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderB)

    when:
    def actualGitInfo = gitInfoProvider.getGitInfo(REPO_PATH)

    then:
    actualGitInfo.repositoryURL == "repoUrl"
    actualGitInfo.commit.sha == "sha"
    actualGitInfo.commit.fullMessage == "message"
    actualGitInfo.commit.author.name == "author name"
    actualGitInfo.commit.author.email == "author email"
    actualGitInfo.commit.author.iso8601Date == "author date"
    actualGitInfo.commit.committer.name == "committer name"
    actualGitInfo.commit.committer.email == "committer email"
    actualGitInfo.commit.committer.iso8601Date == "committer date"
  }

  def "test falls back to the second GitInfoBuilder for empty strings in commit info"() {
    setup:
    def gitInfoBuilderA = givenABuilderReturning(
      new GitInfo("repoUrl", null, null,
      new CommitInfo("sha",
      new PersonInfo("", "", ""),
      new PersonInfo("", "", ""),
      "")))

    def gitInfoBuilderB = givenABuilderReturning(
      new GitInfo("repoUrl", null, null,
      new CommitInfo("sha",
      new PersonInfo("author name", "author email", "author date"),
      new PersonInfo("committer name", "committer email", "committer date"),
      "message")))

    def gitInfoProvider = new GitInfoProvider()
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderA)
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderB)

    when:
    def actualGitInfo = gitInfoProvider.getGitInfo(REPO_PATH)

    then:
    actualGitInfo.repositoryURL == "repoUrl"
    actualGitInfo.commit.sha == "sha"
    actualGitInfo.commit.fullMessage == "message"
    actualGitInfo.commit.author.name == "author name"
    actualGitInfo.commit.author.email == "author email"
    actualGitInfo.commit.author.iso8601Date == "author date"
    actualGitInfo.commit.committer.name == "committer name"
    actualGitInfo.commit.committer.email == "committer email"
    actualGitInfo.commit.committer.iso8601Date == "committer date"
  }

  def "test falls back to the second GitInfoBuilder for blank strings in commit info"() {
    setup:
    def gitInfoBuilderA = givenABuilderReturning(
      new GitInfo("repoUrl", null, null,
      new CommitInfo("sha",
      new PersonInfo(" ", " ", " "),
      new PersonInfo(" ", " ", " "),
      " ")))

    def gitInfoBuilderB = givenABuilderReturning(
      new GitInfo("repoUrl", null, null,
      new CommitInfo("sha",
      new PersonInfo("author name", "author email", "author date"),
      new PersonInfo("committer name", "committer email", "committer date"),
      "message")))

    def gitInfoProvider = new GitInfoProvider()
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderA)
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderB)

    when:
    def actualGitInfo = gitInfoProvider.getGitInfo(REPO_PATH)

    then:
    actualGitInfo.repositoryURL == "repoUrl"
    actualGitInfo.commit.sha == "sha"
    actualGitInfo.commit.fullMessage == "message"
    actualGitInfo.commit.author.name == "author name"
    actualGitInfo.commit.author.email == "author email"
    actualGitInfo.commit.author.iso8601Date == "author date"
    actualGitInfo.commit.committer.name == "committer name"
    actualGitInfo.commit.committer.email == "committer email"
    actualGitInfo.commit.committer.iso8601Date == "committer date"
  }

  def "test does not fall back to the second GitInfoBuilder for commit info if SHAs do not match"() {
    setup:
    def gitInfoBuilderA = givenABuilderReturning(
      new GitInfo("repoUrl", null, null,
      new CommitInfo("sha",
      PersonInfo.NOOP,
      PersonInfo.NOOP,
      "message")))

    def gitInfoBuilderB = givenABuilderReturning(
      new GitInfo("repoUrl", null, null,
      new CommitInfo("different sha",
      new PersonInfo("author name", "author email", "author date"),
      new PersonInfo("committer name", "committer email", "committer date"),
      "message")))

    def gitInfoProvider = new GitInfoProvider()
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderA)
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderB)

    when:
    def actualGitInfo = gitInfoProvider.getGitInfo(REPO_PATH)

    then:
    actualGitInfo.repositoryURL == "repoUrl"
    actualGitInfo.commit.sha == "sha"
    actualGitInfo.commit.fullMessage == "message"
    actualGitInfo.commit.author.name == null
    actualGitInfo.commit.author.email == null
    actualGitInfo.commit.author.iso8601Date == null
    actualGitInfo.commit.committer.name == null
    actualGitInfo.commit.committer.email == null
    actualGitInfo.commit.committer.iso8601Date == null
  }

  def "test ignores remote URLs starting with file protocol"() {
    setup:
    def gitInfoBuilderA = givenABuilderReturning(
      new GitInfo("file://uselessUrl", null, null, new CommitInfo(null)), 1
      )

    def gitInfoBuilderB = givenABuilderReturning(
      new GitInfo("http://usefulUrl", null, null, new CommitInfo(null)), 2
      )

    def gitInfoProvider = new GitInfoProvider()
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderA)
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderB)

    when:
    def actualGitInfo = gitInfoProvider.getGitInfo(REPO_PATH)

    then:
    actualGitInfo.repositoryURL == "http://usefulUrl"
  }

  private GitInfoBuilder givenABuilderReturning(GitInfo gitInfo) {
    givenABuilderReturning(gitInfo, 1)
  }

  private GitInfoBuilder givenABuilderReturning(GitInfo gitInfo, int order) {
    def gitInfoBuilder = Stub(GitInfoBuilder)
    gitInfoBuilder.build(REPO_PATH) >> gitInfo
    gitInfoBuilder.order() >> order
    gitInfoBuilder
  }
}
