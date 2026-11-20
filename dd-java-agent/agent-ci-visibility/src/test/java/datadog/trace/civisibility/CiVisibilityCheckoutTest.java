package datadog.trace.civisibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import datadog.communication.util.IOUtils;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.civisibility.ci.CIProviderInfo;
import datadog.trace.civisibility.ci.PullRequestInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import datadog.trace.civisibility.git.tree.GitClient;
import datadog.trace.civisibility.git.tree.ShellGitClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CiVisibilityCheckoutTest {
  @Test
  void findsGitRootFromTheTestedPath(@TempDir Path workspace) throws Exception {
    Path nested = workspace.resolve("tools/module");
    GitClient.Factory factory = mock(GitClient.Factory.class);
    GitClient client = mock(GitClient.class);
    when(factory.create(nested.toString())).thenReturn(client);
    when(client.getRepoRoot()).thenReturn(workspace.resolve("tools").toString());
    assertEquals(
        workspace.resolve("tools").toString(),
        CiVisibilityRepoServices.getRepoRoot(factory, nested));
    when(client.getRepoRoot()).thenThrow(new java.io.IOException("no checkout"));
    assertNull(CiVisibilityRepoServices.getRepoRoot(factory, nested));
  }

  @Test
  void nestedNativeCheckoutWinsOverTheEnclosingRepository(@TempDir Path workspace)
      throws Exception {
    Path fixture = Paths.get(getClass().getResource("/ci/git/with_pack/git").toURI());
    Path nested = Files.createDirectories(workspace.resolve("tools"));
    IOUtils.copyFolder(fixture, Files.createDirectory(workspace.resolve(".git")));
    IOUtils.copyFolder(fixture, Files.createDirectory(nested.resolve(".git")));
    Path module = Files.createDirectory(nested.resolve("module"));
    Config config = mock(Config.class);
    when(config.getCiVisibilityGitCommandTimeoutMillis()).thenReturn(10_000L);
    assertEquals(
        nested.toRealPath().toString(),
        CiVisibilityRepoServices.getRepoRoot(
            new ShellGitClient.Factory(config, mock(CiVisibilityMetricCollector.class)), module));
  }

  @Test
  void foreignCheckoutUsesOnlyExplicitPrOverridesWithoutReadingProviderAncestry() throws Exception {
    Config config = mock(Config.class);
    when(config.getGitPullRequestBaseBranch()).thenReturn("explicit-base");
    when(config.getGitCommitHeadSha()).thenReturn("explicit-head");
    CIProviderInfo provider = mock(CIProviderInfo.class);
    CiEnvironment env = mock(CiEnvironment.class);
    GitClient git = mock(GitClient.class);
    PullRequestInfo pr =
        CiVisibilityRepoServices.buildPullRequestInfo(config, env, provider, git, false);
    assertEquals("explicit-base", pr.getBaseBranch());
    assertEquals("explicit-head", pr.getHeadCommit().getSha());
    verifyNoInteractions(provider, env, git);
  }
}
