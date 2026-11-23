package datadog.trace.civisibility;

import static datadog.trace.api.civisibility.telemetry.tag.Provider.GITHUBACTIONS;
import static datadog.trace.civisibility.CiVisibilityRepoServicesTestFixture.createServices;
import static datadog.trace.civisibility.CiVisibilityRepoServicesTestFixture.settingsPullRequestInfo;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.BazelMode;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.civisibility.ci.CIInfo;
import datadog.trace.civisibility.ci.CIProviderInfo;
import datadog.trace.civisibility.ci.CIProviderInfoFactory;
import datadog.trace.civisibility.ci.PullRequestInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import datadog.trace.civisibility.git.tree.GitClient;
import datadog.trace.civisibility.source.index.RepoIndexProvider;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.tabletest.junit.TableTest;

class CiVisibilityBazelCheckoutForkedTest {
  private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  @TempDir Path workspace;

  @TableTest({
    "Scenario                     | enabled | virtualRoot | nested | source   | checkoutTag | customTag | ciTarget",
    "virtual runfiles workspace   | true    | true        | false  | runfiles | false       | false     | true    ",
    "Bazel without a virtual root | true    | false       | false  | checkout | true        | false     | true    ",
    "disabled Bazel with runfiles | false   | true        | false  | checkout | true        | false     | true    ",
    "nested Git checkout          | false   | false       | true   | checkout | true        | true      | false   ",
    "Bazel with nested checkout   | true    | false       | true   | checkout | true        | true      | false   "
  })
  void distinguishesRunfilesFromDiscoveredCheckouts(
      boolean enabled,
      boolean virtualRoot,
      boolean nested,
      String source,
      boolean checkoutTag,
      boolean customTag,
      boolean ciTarget)
      throws Exception {
    Path checkout = Files.createDirectories(workspace.resolve(nested ? "tools" : "checkout"));
    Path ciWorkspace = (nested ? workspace : checkout).toRealPath();
    Path runfiles = Files.createDirectories(workspace.resolve("test.runfiles/project"));
    Path tested =
        Files.createDirectories((enabled && virtualRoot ? runfiles : checkout).resolve("module"));
    BazelMode mode = mock(BazelMode.class);
    when(mode.isEnabled()).thenReturn(enabled);
    when(mode.isManifestModeEnabled()).thenReturn(enabled);
    when(mode.getRepoRoot()).thenReturn(virtualRoot ? runfiles.toString() : null);
    Field singleton = BazelMode.class.getDeclaredField("INSTANCE");
    singleton.setAccessible(true);
    Object original = singleton.get(null);
    try {
      singleton.set(null, mode);
      Config config = mock(Config.class);
      when(config.getGlobalTags()).thenReturn(emptyMap());
      CiEnvironment environment = mock(CiEnvironment.class);
      CIProviderInfo provider = mock(CIProviderInfo.class);
      when(provider.getProvider()).thenReturn(GITHUBACTIONS);
      when(provider.buildCIInfo())
          .thenReturn(CIInfo.builder(environment).ciWorkspace(ciWorkspace.toString()).build());
      when(provider.buildCIGitInfo())
          .thenReturn(new GitInfo(null, null, null, new CommitInfo(SHA)));
      when(provider.buildPullRequestInfo())
          .thenReturn(new PullRequestInfo("main", null, null, CommitInfo.NOOP, "42"));
      CIProviderInfoFactory providers = mock(CIProviderInfoFactory.class);
      when(providers.createCIProviderInfo(tested)).thenReturn(provider);
      GitClient git = mock(GitClient.class);
      when(git.getRepoRoot()).thenReturn(checkout.toString());
      GitClient.Factory clients = mock(GitClient.Factory.class);
      when(clients.create(anyString())).thenReturn(git);
      GitInfoProvider selected = mock(GitInfoProvider.class);
      when(selected.getGitInfo(anyString()))
          .thenReturn(new GitInfo(null, null, null, new CommitInfo(SHA)));
      ProcessHierarchy hierarchy = mock(ProcessHierarchy.class);
      when(hierarchy.isHeadless()).thenReturn(true);
      RepoIndexProvider.Factory indexes = mock(RepoIndexProvider.Factory.class);
      when(indexes.create(anyString())).thenReturn(mock(RepoIndexProvider.class));
      CiVisibilityServices services =
          createServices(config, environment, providers, clients, selected, hierarchy, indexes);
      CiVisibilityRepoServices repository = new CiVisibilityRepoServices(services, tested);
      verify(indexes).create(("runfiles".equals(source) ? runfiles : checkout).toString());
      assertAll(
          () ->
              assertEquals(
                  checkoutTag ? checkout.toString() : null,
                  repository.ciTags.get("git.checkout.root")),
          () ->
              assertEquals(
                  customTag ? "true" : null, repository.ciTags.get("git.checkout.is_custom")),
          () -> assertEquals(ciWorkspace.toString(), repository.ciTags.get("ci.workspace_path")),
          () ->
              assertEquals(
                  ciTarget ? "main" : null, settingsPullRequestInfo(repository).getBaseBranch()));
    } finally {
      singleton.set(null, original);
    }
  }
}
