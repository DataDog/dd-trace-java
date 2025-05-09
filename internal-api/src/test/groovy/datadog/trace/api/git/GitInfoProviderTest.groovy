package datadog.trace.api.git

import datadog.trace.api.civisibility.InstrumentationBridge
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector
import datadog.trace.api.civisibility.telemetry.tag.GitProviderDiscrepant
import datadog.trace.api.civisibility.telemetry.tag.GitProviderExpected
import datadog.trace.api.civisibility.telemetry.tag.GitShaDiscrepancyType
import datadog.trace.api.civisibility.telemetry.tag.GitShaMatch
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

  def "test adds correct telemetry metrics when SHA discrepancies are found"() {
    setup:
    def metricCollector = Mock(CiVisibilityMetricCollector)
    InstrumentationBridge.registerMetricCollector(metricCollector)

    def gitInfoA = new GitInfo("repoUrlA", null, null,
      new CommitInfo("shaA",
      PersonInfo.NOOP,
      PersonInfo.NOOP,
      "message"
      ))
    def gitInfoB = new GitInfo("repoUrlA", null, null,
      new CommitInfo("shaB",
      new PersonInfo("author name", "author email", "author date"),
      new PersonInfo("committer name", "committer email", "committer date"),
      "message"
      ))
    def gitInfoC = new GitInfo("repoUrlB", null, null,
      new CommitInfo("shaC",
      new PersonInfo("author name", "author email", "author date"),
      new PersonInfo("committer name", "committer email", "committer date"),
      "message"
      ))

    def gitInfoBuilderA = givenABuilderReturning(gitInfoA, 1, GitProviderExpected.CI_PROVIDER, GitProviderDiscrepant.CI_PROVIDER)
    def gitInfoBuilderB = givenABuilderReturning(gitInfoB, 2, GitProviderExpected.LOCAL_GIT, GitProviderDiscrepant.LOCAL_GIT)
    def gitInfoBuilderC = givenABuilderReturning(gitInfoC, 3, GitProviderExpected.GIT_CLIENT, GitProviderDiscrepant.GIT_CLIENT)

    def gitInfoProvider = new GitInfoProvider()
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderA)
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderB)
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderC)

    when:
    gitInfoProvider.getGitInfo(REPO_PATH)

    then:
    1 * metricCollector.add(CiVisibilityCountMetric.GIT_COMMIT_SHA_MATCH, 1, GitShaMatch.FALSE)
    1 * metricCollector.add(CiVisibilityCountMetric.GIT_COMMIT_SHA_DISCREPANCY, 1, GitProviderExpected.CI_PROVIDER, GitProviderDiscrepant.LOCAL_GIT, GitShaDiscrepancyType.COMMIT_DISCREPANCY)
    1 * metricCollector.add(CiVisibilityCountMetric.GIT_COMMIT_SHA_DISCREPANCY, 1, GitProviderExpected.CI_PROVIDER, GitProviderDiscrepant.GIT_CLIENT, GitShaDiscrepancyType.REPOSITORY_DISCREPANCY)
  }

  def "test adds correct telemetry metrics when no SHA discrepancies are found"() {
    setup:
    def metricCollector = Mock(CiVisibilityMetricCollector)
    InstrumentationBridge.registerMetricCollector(metricCollector)

    def gitInfoA = new GitInfo("repoUrlA", null, null,
      new CommitInfo("shaA",
      PersonInfo.NOOP,
      PersonInfo.NOOP,
      "message"
      ))
    def gitInfoB = new GitInfo("repoUrlA", null, null,
      new CommitInfo("shaA",
      new PersonInfo("author name", "author email", "author date"),
      new PersonInfo("committer name", "committer email", "committer date"),
      "message"
      ))

    def gitInfoBuilderA = givenABuilderReturning(gitInfoA, 1, GitProviderExpected.CI_PROVIDER, GitProviderDiscrepant.CI_PROVIDER)
    def gitInfoBuilderB = givenABuilderReturning(gitInfoB, 2, GitProviderExpected.LOCAL_GIT, GitProviderDiscrepant.LOCAL_GIT)

    def gitInfoProvider = new GitInfoProvider()
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderA)
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderB)

    when:
    gitInfoProvider.getGitInfo(REPO_PATH)

    then:
    1 * metricCollector.add(CiVisibilityCountMetric.GIT_COMMIT_SHA_MATCH, 1, GitShaMatch.TRUE)
    0 * metricCollector.add(CiVisibilityCountMetric.GIT_COMMIT_SHA_DISCREPANCY, *_)
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
    givenABuilderReturning(gitInfo, order, GitProviderExpected.USER_SUPPLIED, GitProviderDiscrepant.USER_SUPPLIED)
  }

  private GitInfoBuilder givenABuilderReturning(GitInfo gitInfo, int order, GitProviderExpected expected, GitProviderDiscrepant discrepant) {
    def gitInfoBuilder = Stub(GitInfoBuilder)
    gitInfoBuilder.build(REPO_PATH) >> gitInfo
    gitInfoBuilder.order() >> order
    gitInfoBuilder.providerAsExpected() >> expected
    gitInfoBuilder.providerAsDiscrepant() >> discrepant
    gitInfoBuilder
  }
}
