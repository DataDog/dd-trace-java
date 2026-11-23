package datadog.trace.civisibility;

import static datadog.communication.util.IOUtils.copyFolder;
import static datadog.trace.api.git.UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_SHA;
import static datadog.trace.bootstrap.instrumentation.api.Tags.GIT_COMMIT_SHA;
import static datadog.trace.civisibility.CiVisibilityRepoServicesTestFixture.createServices;
import static datadog.trace.civisibility.CiVisibilityRepoServicesTestFixture.settingsPullRequestInfo;
import static datadog.trace.civisibility.ci.TeamcityInfo.TEAMCITY;
import static datadog.trace.civisibility.git.tree.GitTestRepository.GIT_COMMAND_TIMEOUT_MILLIS;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.api.git.PersonInfo;
import datadog.trace.civisibility.ci.CIInfo;
import datadog.trace.civisibility.ci.CIProviderInfo;
import datadog.trace.civisibility.ci.CIProviderInfoFactory;
import datadog.trace.civisibility.ci.PullRequestInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import datadog.trace.civisibility.ci.env.CiEnvironmentImpl;
import datadog.trace.civisibility.git.tree.GitClient;
import datadog.trace.civisibility.git.tree.NoOpGitClient;
import datadog.trace.civisibility.git.tree.ShellGitClient;
import datadog.trace.civisibility.source.index.RepoIndexProvider;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.tabletest.junit.TableTest;

@WithConfig(key = DD_GIT_COMMIT_SHA, value = "")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class CiVisibilityCheckoutTest {
  private static final String HEAD_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final String OTHER_SHA = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
  @TempDir Path checkout;

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
    copyFolder(fixture, Files.createDirectory(workspace.resolve(".git")));
    copyFolder(fixture, Files.createDirectory(nested.resolve(".git")));
    Path module = Files.createDirectory(nested.resolve("module"));
    Config config = mock(Config.class);
    when(config.getCiVisibilityGitCommandTimeoutMillis()).thenReturn(GIT_COMMAND_TIMEOUT_MILLIS);
    assertEquals(
        nested.toRealPath().toString(),
        CiVisibilityRepoServices.getRepoRoot(
            new ShellGitClient.Factory(config, mock(CiVisibilityMetricCollector.class)), module));
  }

  @TableTest({
    "Scenario              | root            | module     ",
    "module with spaces    | repo            | test suite ",
    "Unicode module        | repo            | módulo-测试",
    "root with spaces      | repo with space | module     ",
    "Unicode root          | dépôt-源码      | module     ",
    "dots within root name | repo..name      | module     "
  })
  void discoversValidFilesystemPathsWithoutACiWorkspace(String root, String module)
      throws Exception {
    Path repository = Files.createDirectories(checkout.resolve(root));
    Path tested = Files.createDirectories(repository.resolve(module));
    Path fixture = Paths.get(getClass().getResource("/ci/git/with_pack/git").toURI());
    copyFolder(fixture, Files.createDirectory(repository.resolve(".git")));
    Config config = mock(Config.class);
    when(config.getCiVisibilityGitCommandTimeoutMillis()).thenReturn(GIT_COMMAND_TIMEOUT_MILLIS);
    when(config.isCiVisibilityCiProviderIntegrationEnabled()).thenReturn(true);
    CIProviderInfo teamcity =
        new CIProviderInfoFactory(config, new CiEnvironmentImpl(singletonMap(TEAMCITY, "2026.1")))
            .createCIProviderInfo(tested);
    assertNull(teamcity.buildCIInfo().getCiWorkspace());
    GitClient.Factory clients =
        new ShellGitClient.Factory(config, mock(CiVisibilityMetricCollector.class));
    String detected = CiVisibilityRepoServices.getRepoRoot(clients, tested);
    assertEquals(repository.toRealPath().toString(), detected);
    assertEquals(
        "5b6f3a6dab5972d73a56dff737bd08d995255c08", clients.create(detected).getSha("HEAD"));
  }

  @TableTest({
    "Scenario     | path               ",
    "missing      |                    ",
    "empty        | ''                 ",
    "blank        | ' '                ",
    "invalid path | 'invalid\u0000path'"
  })
  void ignoresMissingOrInvalidWorkingDirectories(String path) {
    GitClient.Factory clients =
        new ShellGitClient.Factory(mock(Config.class), mock(CiVisibilityMetricCollector.class));
    assertSame(NoOpGitClient.INSTANCE, clients.create(path));
  }

  @Test
  void foreignCheckoutEnrichesExplicitHeadWithoutReadingProviderAncestry() throws Exception {
    Config config = mock(Config.class);
    when(config.getGitPullRequestBaseBranch()).thenReturn("explicit-base");
    when(config.getGitCommitHeadSha()).thenReturn(HEAD_SHA);
    CIProviderInfo provider = mock(CIProviderInfo.class);
    CiEnvironment env = mock(CiEnvironment.class);
    GitClient git = mock(GitClient.class);
    when(git.getCommitInfo(HEAD_SHA, true))
        .thenReturn(
            new CommitInfo(HEAD_SHA, PersonInfo.NOOP, PersonInfo.NOOP, "explicit head message"));
    PullRequestInfo pr =
        CiVisibilityRepoServices.buildPullRequestInfo(config, env, provider, git, false);
    assertEquals("explicit-base", pr.getBaseBranch());
    assertEquals(HEAD_SHA, pr.getHeadCommit().getSha());
    assertEquals("explicit head message", pr.getHeadCommit().getFullMessage());
    verify(git).getCommitInfo(HEAD_SHA, true);
    verifyNoInteractions(provider, env);
  }

  @TableTest({
    "Scenario                    | custom | providerMismatch | explicitOverride | ddciMismatch | useTarget",
    "TeamCity without a revision | false  | false            | false            | false        | true     ",
    "known checkout mismatch     | true   | false            | false            | false        | false    ",
    "known revision mismatch     | false  | true             | false            | false        | false    ",
    "explicit revision override  | false  | false            | true             | false        | false    ",
    "known DDCI mismatch         | false  | false            | false            | true         | false    "
  })
  void preservesDeclaredTargetsWithoutBorrowingKnownForeignAncestry(
      boolean custom,
      boolean providerMismatch,
      boolean explicitOverride,
      boolean ddciMismatch,
      boolean useTarget)
      throws Exception {
    Config config = mock(Config.class);
    when(config.isCiVisibilityCiProviderIntegrationEnabled()).thenReturn(true);
    when(config.getGlobalTags())
        .thenReturn(explicitOverride ? singletonMap(GIT_COMMIT_SHA, HEAD_SHA) : emptyMap());
    Map<String, String> values = new HashMap<>();
    values.put(TEAMCITY, "2026.1");
    values.put("TEAMCITY_PULLREQUEST_TARGET_BRANCH", "release/1.x");
    if (ddciMismatch) {
      values.put(Constants.DDCI_PULL_REQUEST_SOURCE_SHA, OTHER_SHA);
    }
    CiEnvironment environment = new CiEnvironmentImpl(values);
    CIProviderInfo provider =
        spy(new CIProviderInfoFactory(config, environment).createCIProviderInfo(checkout));
    when(provider.buildCIInfo())
        .thenReturn(
            CIInfo.builder(environment)
                .ciWorkspace(custom ? checkout.resolve("workflow").toString() : checkout.toString())
                .build());
    if (providerMismatch) {
      when(provider.buildCIGitInfo())
          .thenReturn(new GitInfo(null, null, null, new CommitInfo(OTHER_SHA)));
    }
    CIProviderInfoFactory providerFactory = mock(CIProviderInfoFactory.class);
    when(providerFactory.createCIProviderInfo(checkout)).thenReturn(provider);
    GitClient git = mock(GitClient.class);
    when(git.getRepoRoot()).thenReturn(checkout.toString());
    GitClient.Factory clients = mock(GitClient.Factory.class);
    when(clients.create(anyString())).thenReturn(git);
    GitInfoProvider selected = mock(GitInfoProvider.class);
    when(selected.getGitInfo(checkout.toString()))
        .thenReturn(new GitInfo(null, null, null, new CommitInfo(HEAD_SHA)));
    ProcessHierarchy hierarchy = mock(ProcessHierarchy.class);
    when(hierarchy.isHeadless()).thenReturn(true);
    RepoIndexProvider.Factory indexes = mock(RepoIndexProvider.Factory.class);
    when(indexes.create(checkout.toString())).thenReturn(mock(RepoIndexProvider.class));
    CiVisibilityServices services =
        createServices(config, environment, providerFactory, clients, selected, hierarchy, indexes);
    CiVisibilityRepoServices repository = new CiVisibilityRepoServices(services, checkout);
    assertEquals(
        useTarget ? "release/1.x" : null, settingsPullRequestInfo(repository).getBaseBranch());
  }
}
