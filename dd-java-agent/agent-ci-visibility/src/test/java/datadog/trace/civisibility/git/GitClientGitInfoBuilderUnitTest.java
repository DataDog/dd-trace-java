package datadog.trace.civisibility.git;

import static datadog.trace.civisibility.git.tree.GitTestRepository.GIT_COMMAND_TIMEOUT_MILLIS;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.communication.util.IOUtils;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.civisibility.ci.CIInfo;
import datadog.trace.civisibility.ci.CIProviderInfo;
import datadog.trace.civisibility.ci.CIProviderInfoFactory;
import datadog.trace.civisibility.ci.PullRequestInfo;
import datadog.trace.civisibility.git.tree.GitClient;
import datadog.trace.civisibility.git.tree.ShellGitClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tabletest.junit.TableTest;

class GitClientGitInfoBuilderUnitTest {
  @TempDir Path workspace;

  @Test
  void keepsCheckoutMetadataWhenNoRemoteIsConfigured(@TempDir Path checkout) throws Exception {
    IOUtils.copyFolder(
        Paths.get(getClass().getClassLoader().getResource("ci/git/with_pack/git").toURI()),
        Files.createDirectories(checkout.resolve(".git")));
    Files.delete(checkout.resolve(".git/config"));
    Config config = mock(Config.class);
    when(config.getCiVisibilityGitRemoteName()).thenReturn("origin");
    when(config.getCiVisibilityGitCommandTimeoutMillis()).thenReturn(GIT_COMMAND_TIMEOUT_MILLIS);
    GitInfo info =
        new GitClientGitInfoBuilder(
                config, new ShellGitClient.Factory(config, mock(CiVisibilityMetricCollector.class)))
            .build(checkout.toString());
    assertNull(info.getRepositoryURL());
    assertEquals("master", info.getBranch());
    assertEquals("5b6f3a6dab5972d73a56dff737bd08d995255c08", info.getCommit().getSha());
    assertEquals("Tony Redondo", info.getCommit().getAuthor().getName());
  }

  @Test
  void interruptedRemoteLookupAbortsAndPreservesInterruption() throws Exception {
    GitClient client = mock(GitClient.class);
    when(client.getRemoteUrl("origin")).thenThrow(new InterruptedException());
    try {
      assertEquals(GitInfo.NOOP, builder(client).build("/repo"));
      assertTrue(Thread.currentThread().isInterrupted());
      verify(client, never()).getCurrentBranch();
    } finally {
      Thread.interrupted();
    }
    assertFalse(Thread.currentThread().isInterrupted());
  }

  @Test
  void timedOutRemoteLookupAborts() throws Exception {
    GitClient client = mock(GitClient.class);
    when(client.getRemoteUrl("origin")).thenThrow(new TimeoutException());
    assertEquals(GitInfo.NOOP, builder(client).build("/repo"));
    verify(client, never()).getCurrentBranch();
  }

  @Test
  void tagsAreOnlyInferredForAnUnambiguousDetachedCheckout() throws Exception {
    GitClient client = mock(GitClient.class);
    when(client.getCommitInfo(GitClient.HEAD, false)).thenReturn(new CommitInfo("local-sha"));
    when(client.getCurrentBranch()).thenReturn("main", "", "");
    when(client.getTags(GitClient.HEAD)).thenReturn(singletonList("v1"), asList("v1", "stable"));
    GitClientGitInfoBuilder builder = builder(client);
    assertNull(builder.build("/repo").getTag());
    assertEquals("v1", builder.build("/repo").getTag());
    assertNull(builder.build("/repo").getTag());
  }

  @TableTest({
    "Scenario                   | Branch | Revision | Custom | Expected branch | Expected URL                    | Expected tag",
    "detached provider revision | ''     | provider | false  | feature         | https://github.com/org/repo.git |             ",
    "detached PR head           | ''     | head     | false  | feature         | https://github.com/org/repo.git |             ",
    "detached merge revision    | ''     | merge    | false  | feature         | https://github.com/org/repo.git |             ",
    "different revision         | ''     | other    | false  | ''              | https://mirror/repo.git         | v1          ",
    "nested checkout            | ''     | provider | true   | ''              | https://mirror/repo.git         | v1          ",
    "attached branch            | local  | provider | false  | local           | https://mirror/repo.git         |             "
  })
  void borrowsCiIdentityOnlyForMatchedDetachedCheckouts(
      String branch,
      String revision,
      boolean custom,
      String expectedBranch,
      String expectedUrl,
      String expectedTag)
      throws Exception {
    String sha = "1111111111111111111111111111111111111111";
    String head = "2222222222222222222222222222222222222222";
    String merge = "4444444444444444444444444444444444444444";
    String actual =
        "provider".equals(revision)
            ? sha
            : "head".equals(revision)
                ? head
                : "merge".equals(revision) ? merge : "3333333333333333333333333333333333333333";
    Path checkout = custom ? Files.createDirectory(workspace.resolve("tools")) : workspace;
    GitClient client = mock(GitClient.class);
    when(client.getRepoRoot()).thenReturn(checkout.toString());
    when(client.getCurrentBranch()).thenReturn(branch);
    when(client.getRemoteUrl("origin")).thenReturn("https://mirror/repo.git");
    when(client.getTags(GitClient.HEAD)).thenReturn(singletonList("v1"));
    CommitInfo commit = new CommitInfo(actual);
    when(client.getCommitInfo(GitClient.HEAD, false)).thenReturn(commit);
    Config config = mock(Config.class);
    when(config.getCiVisibilityGitRemoteName()).thenReturn("origin");
    GitClient.Factory factory = mock(GitClient.Factory.class);
    when(factory.create(checkout.toString())).thenReturn(client);
    CIProviderInfo provider = mock(CIProviderInfo.class);
    when(provider.buildCIInfo())
        .thenReturn(CIInfo.builder(null).ciWorkspace(workspace.toString()).build());
    when(provider.buildCIGitInfo())
        .thenReturn(
            new GitInfo("https://github.com/org/repo.git", "feature", null, new CommitInfo(sha)));
    when(provider.getMergeCommitSha()).thenReturn(merge);
    when(provider.buildPullRequestInfo())
        .thenReturn(new PullRequestInfo(null, null, null, new CommitInfo(head), null));
    CIProviderInfoFactory ciFactory = mock(CIProviderInfoFactory.class);
    when(ciFactory.createCIProviderInfo(checkout)).thenReturn(provider);
    GitInfo info =
        new GitClientGitInfoBuilder(config, factory, ciFactory).build(checkout.toString());
    assertEquals(expectedBranch, info.getBranch());
    assertEquals(expectedUrl, info.getRepositoryURL());
    assertEquals(expectedTag, info.getTag());
    assertEquals(commit, info.getCommit());
  }

  private GitClientGitInfoBuilder builder(GitClient client) {
    Config config = mock(Config.class);
    when(config.getCiVisibilityGitRemoteName()).thenReturn("origin");
    GitClient.Factory factory = mock(GitClient.Factory.class);
    when(factory.create("/repo")).thenReturn(client);
    return new GitClientGitInfoBuilder(config, factory);
  }
}
