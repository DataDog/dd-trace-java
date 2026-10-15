package datadog.trace.api.git;

import static datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric.GIT_COMMIT_SHA_DISCREPANCY;
import static datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric.GIT_COMMIT_SHA_MATCH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.TagValue;
import datadog.trace.api.civisibility.telemetry.tag.GitProviderDiscrepant;
import datadog.trace.api.civisibility.telemetry.tag.GitProviderExpected;
import datadog.trace.api.civisibility.telemetry.tag.GitShaDiscrepancyType;
import datadog.trace.api.civisibility.telemetry.tag.GitShaMatch;
import org.junit.jupiter.api.Test;

class GitInfoProviderTest {

  private static final String REPO_PATH = "/repo/path";

  private static final PersonInfo AUTHOR =
      new PersonInfo("author name", "author email", "author date");
  private static final PersonInfo COMMITTER =
      new PersonInfo("committer name", "committer email", "committer date");

  @Test
  void testDelegatesToGitInfoBuilder() {
    GitInfoBuilder gitInfoBuilder =
        givenABuilderReturning(new GitInfo("repoUrl", "branch", "tag", new CommitInfo("sha")));

    GitInfoProvider gitInfoProvider = new GitInfoProvider();
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilder);

    GitInfo actualGitInfo = gitInfoProvider.getGitInfo(REPO_PATH);

    assertEquals("repoUrl", actualGitInfo.getRepositoryURL());
    assertEquals("branch", actualGitInfo.getBranch());
    assertEquals("tag", actualGitInfo.getTag());
    assertEquals("sha", actualGitInfo.getCommit().getSha());
  }

  @Test
  void testFallsBackToTheSecondGitInfoBuilder() {
    GitInfoBuilder gitInfoBuilderA =
        givenABuilderReturning(new GitInfo("repoUrl", null, null, new CommitInfo(null)));

    GitInfoBuilder gitInfoBuilderB =
        givenABuilderReturning(new GitInfo(null, "branch", "tag", new CommitInfo("sha")), 2);

    GitInfoProvider gitInfoProvider = new GitInfoProvider();
    // registering provider with higher order first, to check that the registration logic will do
    // proper reordering after the second registration
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderB);
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderA);

    GitInfo actualGitInfo = gitInfoProvider.getGitInfo(REPO_PATH);

    assertEquals("repoUrl", actualGitInfo.getRepositoryURL());
    assertEquals("branch", actualGitInfo.getBranch());
    assertEquals("tag", actualGitInfo.getTag());
    assertEquals("sha", actualGitInfo.getCommit().getSha());
  }

  @Test
  void testFallsBackToTheSecondGitInfoBuilderForEmptyStrings() {
    GitInfoBuilder gitInfoBuilderA =
        givenABuilderReturning(new GitInfo("repoUrl", "", "", new CommitInfo("")));

    GitInfoBuilder gitInfoBuilderB =
        givenABuilderReturning(new GitInfo(null, "branch", "tag", new CommitInfo("sha")));

    GitInfoProvider gitInfoProvider = new GitInfoProvider();
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderA);
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderB);

    GitInfo actualGitInfo = gitInfoProvider.getGitInfo(REPO_PATH);

    assertEquals("repoUrl", actualGitInfo.getRepositoryURL());
    assertEquals("branch", actualGitInfo.getBranch());
    assertEquals("tag", actualGitInfo.getTag());
    assertEquals("sha", actualGitInfo.getCommit().getSha());
  }

  @Test
  void testFallsBackToTheSecondGitInfoBuilderForBlankStrings() {
    GitInfoBuilder gitInfoBuilderA =
        givenABuilderReturning(new GitInfo("repoUrl", " ", " ", new CommitInfo(" ")));

    GitInfoBuilder gitInfoBuilderB =
        givenABuilderReturning(new GitInfo(null, "branch", "tag", new CommitInfo("sha")));

    GitInfoProvider gitInfoProvider = new GitInfoProvider();
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderA);
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderB);

    GitInfo actualGitInfo = gitInfoProvider.getGitInfo(REPO_PATH);

    assertEquals("repoUrl", actualGitInfo.getRepositoryURL());
    assertEquals("branch", actualGitInfo.getBranch());
    assertEquals("tag", actualGitInfo.getTag());
    assertEquals("sha", actualGitInfo.getCommit().getSha());
  }

  @Test
  void testFallsBackToTheSecondGitInfoBuilderForCommitInfo() {
    GitInfoBuilder gitInfoBuilderA =
        givenABuilderReturning(
            new GitInfo(
                "repoUrl",
                null,
                null,
                new CommitInfo("sha", PersonInfo.NOOP, PersonInfo.NOOP, null)));

    GitInfoBuilder gitInfoBuilderB =
        givenABuilderReturning(
            new GitInfo(
                "repoUrl", null, null, new CommitInfo("sha", AUTHOR, COMMITTER, "message")));

    GitInfoProvider gitInfoProvider = new GitInfoProvider();
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderA);
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderB);

    GitInfo actualGitInfo = gitInfoProvider.getGitInfo(REPO_PATH);

    assertFallbackCommitInfo(actualGitInfo);
  }

  @Test
  void testFallsBackToTheSecondGitInfoBuilderForEmptyStringsInCommitInfo() {
    GitInfoBuilder gitInfoBuilderA =
        givenABuilderReturning(
            new GitInfo(
                "repoUrl",
                null,
                null,
                new CommitInfo("sha", new PersonInfo("", "", ""), new PersonInfo("", "", ""), "")));

    GitInfoBuilder gitInfoBuilderB =
        givenABuilderReturning(
            new GitInfo(
                "repoUrl", null, null, new CommitInfo("sha", AUTHOR, COMMITTER, "message")));

    GitInfoProvider gitInfoProvider = new GitInfoProvider();
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderA);
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderB);

    GitInfo actualGitInfo = gitInfoProvider.getGitInfo(REPO_PATH);

    assertFallbackCommitInfo(actualGitInfo);
  }

  @Test
  void testFallsBackToTheSecondGitInfoBuilderForBlankStringsInCommitInfo() {
    GitInfoBuilder gitInfoBuilderA =
        givenABuilderReturning(
            new GitInfo(
                "repoUrl",
                null,
                null,
                new CommitInfo(
                    "sha", new PersonInfo(" ", " ", " "), new PersonInfo(" ", " ", " "), " ")));

    GitInfoBuilder gitInfoBuilderB =
        givenABuilderReturning(
            new GitInfo(
                "repoUrl", null, null, new CommitInfo("sha", AUTHOR, COMMITTER, "message")));

    GitInfoProvider gitInfoProvider = new GitInfoProvider();
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderA);
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderB);

    GitInfo actualGitInfo = gitInfoProvider.getGitInfo(REPO_PATH);

    assertFallbackCommitInfo(actualGitInfo);
  }

  @Test
  void testDoesNotFallBackToTheSecondGitInfoBuilderForCommitInfoIfShasDoNotMatch() {
    GitInfoBuilder gitInfoBuilderA =
        givenABuilderReturning(
            new GitInfo(
                "repoUrl",
                null,
                null,
                new CommitInfo("sha", PersonInfo.NOOP, PersonInfo.NOOP, "message")));

    GitInfoBuilder gitInfoBuilderB =
        givenABuilderReturning(
            new GitInfo(
                "repoUrl",
                null,
                null,
                new CommitInfo("different sha", AUTHOR, COMMITTER, "message")));

    GitInfoProvider gitInfoProvider = new GitInfoProvider();
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderA);
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderB);

    GitInfo actualGitInfo = gitInfoProvider.getGitInfo(REPO_PATH);

    assertEquals("repoUrl", actualGitInfo.getRepositoryURL());
    CommitInfo commit = actualGitInfo.getCommit();
    assertEquals("sha", commit.getSha());
    assertEquals("message", commit.getFullMessage());
    PersonInfo author = commit.getAuthor();
    assertNull(author.getName());
    assertNull(author.getEmail());
    assertNull(author.getIso8601Date());
    PersonInfo committer = commit.getCommitter();
    assertNull(committer.getName());
    assertNull(committer.getEmail());
    assertNull(committer.getIso8601Date());
  }

  @Test
  void testAddsCorrectTelemetryMetricsWhenShaDiscrepanciesAreFound() {
    CiVisibilityMetricCollector metricCollector = mock(CiVisibilityMetricCollector.class);
    InstrumentationBridge.registerMetricCollector(metricCollector);

    GitInfo gitInfoA =
        new GitInfo(
            "repoUrlA",
            null,
            null,
            new CommitInfo("shaA", PersonInfo.NOOP, PersonInfo.NOOP, "message"));
    GitInfo gitInfoB =
        new GitInfo("repoUrlA", null, null, new CommitInfo("shaB", AUTHOR, COMMITTER, "message"));
    GitInfo gitInfoC =
        new GitInfo("repoUrlB", null, null, new CommitInfo("shaC", AUTHOR, COMMITTER, "message"));

    GitInfoBuilder gitInfoBuilderA =
        givenABuilderReturning(
            gitInfoA, 1, GitProviderExpected.CI_PROVIDER, GitProviderDiscrepant.CI_PROVIDER);
    GitInfoBuilder gitInfoBuilderB =
        givenABuilderReturning(
            gitInfoB, 2, GitProviderExpected.LOCAL_GIT, GitProviderDiscrepant.LOCAL_GIT);
    GitInfoBuilder gitInfoBuilderC =
        givenABuilderReturning(
            gitInfoC, 3, GitProviderExpected.GIT_CLIENT, GitProviderDiscrepant.GIT_CLIENT);

    GitInfoProvider gitInfoProvider = new GitInfoProvider();
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderA);
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderB);
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderC);

    gitInfoProvider.getGitInfo(REPO_PATH);

    verify(metricCollector).add(GIT_COMMIT_SHA_MATCH, 1L, GitShaMatch.FALSE);
    verify(metricCollector)
        .add(
            GIT_COMMIT_SHA_DISCREPANCY,
            1L,
            GitProviderExpected.CI_PROVIDER,
            GitProviderDiscrepant.LOCAL_GIT,
            GitShaDiscrepancyType.COMMIT_DISCREPANCY);
    verify(metricCollector)
        .add(
            GIT_COMMIT_SHA_DISCREPANCY,
            1L,
            GitProviderExpected.CI_PROVIDER,
            GitProviderDiscrepant.GIT_CLIENT,
            GitShaDiscrepancyType.REPOSITORY_DISCREPANCY);
  }

  @Test
  void testAddsCorrectTelemetryMetricsWhenNoShaDiscrepanciesAreFound() {
    CiVisibilityMetricCollector metricCollector = mock(CiVisibilityMetricCollector.class);
    InstrumentationBridge.registerMetricCollector(metricCollector);

    GitInfo gitInfoA =
        new GitInfo(
            "repoUrlA",
            null,
            null,
            new CommitInfo("shaA", PersonInfo.NOOP, PersonInfo.NOOP, "message"));
    GitInfo gitInfoB =
        new GitInfo("repoUrlA", null, null, new CommitInfo("shaA", AUTHOR, COMMITTER, "message"));

    GitInfoBuilder gitInfoBuilderA =
        givenABuilderReturning(
            gitInfoA, 1, GitProviderExpected.CI_PROVIDER, GitProviderDiscrepant.CI_PROVIDER);
    GitInfoBuilder gitInfoBuilderB =
        givenABuilderReturning(
            gitInfoB, 2, GitProviderExpected.LOCAL_GIT, GitProviderDiscrepant.LOCAL_GIT);

    GitInfoProvider gitInfoProvider = new GitInfoProvider();
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderA);
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderB);

    gitInfoProvider.getGitInfo(REPO_PATH);

    verify(metricCollector).add(GIT_COMMIT_SHA_MATCH, 1L, GitShaMatch.TRUE);
    verify(metricCollector, never())
        .add(eq(GIT_COMMIT_SHA_DISCREPANCY), anyLong(), (TagValue[]) any());
  }

  @Test
  void testIgnoresRemoteUrlsStartingWithFileProtocol() {
    GitInfoBuilder gitInfoBuilderA =
        givenABuilderReturning(
            new GitInfo("file://uselessUrl", null, null, new CommitInfo(null)), 1);

    GitInfoBuilder gitInfoBuilderB =
        givenABuilderReturning(
            new GitInfo("http://usefulUrl", null, null, new CommitInfo(null)), 2);

    GitInfoProvider gitInfoProvider = new GitInfoProvider();
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderA);
    gitInfoProvider.registerGitInfoBuilder(gitInfoBuilderB);

    GitInfo actualGitInfo = gitInfoProvider.getGitInfo(REPO_PATH);

    assertEquals("http://usefulUrl", actualGitInfo.getRepositoryURL());
  }

  private static void assertFallbackCommitInfo(GitInfo actualGitInfo) {
    assertEquals("repoUrl", actualGitInfo.getRepositoryURL());

    CommitInfo commit = actualGitInfo.getCommit();
    assertEquals("sha", commit.getSha());
    assertEquals("message", commit.getFullMessage());

    PersonInfo author = commit.getAuthor();
    assertEquals("author name", author.getName());
    assertEquals("author email", author.getEmail());
    assertEquals("author date", author.getIso8601Date());

    PersonInfo committer = commit.getCommitter();
    assertEquals("committer name", committer.getName());
    assertEquals("committer email", committer.getEmail());
    assertEquals("committer date", committer.getIso8601Date());
  }

  private static GitInfoBuilder givenABuilderReturning(GitInfo gitInfo) {
    return givenABuilderReturning(gitInfo, 1);
  }

  private static GitInfoBuilder givenABuilderReturning(GitInfo gitInfo, int order) {
    return givenABuilderReturning(
        gitInfo, order, GitProviderExpected.USER_SUPPLIED, GitProviderDiscrepant.USER_SUPPLIED);
  }

  private static GitInfoBuilder givenABuilderReturning(
      GitInfo gitInfo, int order, GitProviderExpected expected, GitProviderDiscrepant discrepant) {
    GitInfoBuilder gitInfoBuilder = mock(GitInfoBuilder.class);
    when(gitInfoBuilder.build(REPO_PATH)).thenReturn(gitInfo);
    when(gitInfoBuilder.order()).thenReturn(order);
    when(gitInfoBuilder.providerAsExpected()).thenReturn(expected);
    when(gitInfoBuilder.providerAsDiscrepant()).thenReturn(discrepant);
    return gitInfoBuilder;
  }
}
