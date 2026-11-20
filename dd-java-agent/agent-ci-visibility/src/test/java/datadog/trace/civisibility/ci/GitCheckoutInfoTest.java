package datadog.trace.civisibility.ci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.civisibility.ci.env.CiEnvironmentImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tabletest.junit.TableTest;

class GitCheckoutInfoTest {
  private static final String SHA = "abcdef0123456789abcdef0123456789abcdef01";

  @TableTest({
    "Scenario           | Provider      | Values                                                                                        | Expected",
    "github merge       | GITHUBACTIONS | [GITHUB_REF: refs/pull/42/merge, GITHUB_SHA: SHA]                                             | true    ",
    "github head        | GITHUBACTIONS | [GITHUB_REF: refs/pull/42/head, GITHUB_SHA: SHA]                                              | false   ",
    "github branch      | GITHUBACTIONS | [GITHUB_REF: refs/heads/main, GITHUB_SHA: SHA]                                                | false   ",
    "gitlab result      | GITLAB        | [CI_MERGE_REQUEST_IID: 42, CI_MERGE_REQUEST_EVENT_TYPE: merged_result, CI_COMMIT_SHA: SHA]    | true    ",
    "gitlab train       | GITLAB        | [CI_MERGE_REQUEST_IID: 42, CI_MERGE_REQUEST_EVENT_TYPE: merge_train, CI_COMMIT_SHA: SHA]      | true    ",
    "gitlab detached    | GITLAB        | [CI_MERGE_REQUEST_IID: 42, CI_MERGE_REQUEST_EVENT_TYPE: detached, CI_COMMIT_SHA: SHA]         | false   ",
    "azure merge        | AZP           | [BUILD_REASON: PullRequest, BUILD_SOURCEBRANCH: refs/pull/42/merge, BUILD_SOURCEVERSION: SHA] | true    ",
    "azure branch       | AZP           | [BUILD_REASON: PullRequest, BUILD_SOURCEBRANCH: refs/heads/main, BUILD_SOURCEVERSION: SHA]    | false   ",
    "travis merge       | TRAVISCI      | [TRAVIS_EVENT_TYPE: pull_request, TRAVIS_PULL_REQUEST: 42, TRAVIS_COMMIT: SHA]                | true    ",
    "travis push        | TRAVISCI      | [TRAVIS_EVENT_TYPE: push, TRAVIS_PULL_REQUEST: 42, TRAVIS_COMMIT: SHA]                        | false   ",
    "invalid sha        | GITHUBACTIONS | [GITHUB_REF: refs/pull/42/merge, GITHUB_SHA: short]                                           | false   ",
    "unrelated provider | JENKINS       | [GITHUB_REF: refs/pull/42/merge, GITHUB_SHA: SHA]                                             | false   "
  })
  void reportsOnlyProviderMergeRevisions(
      Provider provider, Map<String, String> values, boolean expected) {
    Map<String, String> env = new HashMap<>(values);
    env.replaceAll(
        (key, value) -> "SHA".equals(value) ? SHA.toUpperCase(java.util.Locale.ROOT) : value);
    assertEquals(
        expected ? SHA : null,
        GitCheckoutInfo.mergeCommitSha(provider, new CiEnvironmentImpl(env)),
        env::toString);
  }

  @TableTest({
    "Scenario                    | repoProvider | number | head    | build   | merge",
    "GitHub PR without branch    | github       | 42     | head    | build   | true ",
    "non-GitHub repository       | bitbucket    | 42     | head    | build   | false",
    "missing provider            |              | 42     | head    | build   | false",
    "no PR number                | github       |        | head    | build   | false",
    "non-numeric PR number       | github       | false  | head    | build   | false",
    "missing PR head             | github       | 42     |         | build   | false",
    "abbreviated PR head         | github       | 42     | ABCDEF0 | build   | false",
    "invalid PR head             | github       | 42     | invalid | build   | false",
    "same revision               | github       | 42     | head    | head    | false",
    "same revision ignoring case | github       | 42     | head    | upper   | false",
    "missing build SHA           | github       | 42     | head    |         | false",
    "invalid build SHA           | github       | 42     | head    | invalid | false"
  })
  void appveyorRequiresDistinctFullPrAndBuildRevisions(
      String repoProvider, String number, String head, String build, boolean merge) {
    Map<String, String> values = new HashMap<>();
    values.put("APPVEYOR_REPO_PROVIDER", repoProvider);
    values.put("APPVEYOR_PULL_REQUEST_NUMBER", number);
    values.put("APPVEYOR_PULL_REQUEST_HEAD_COMMIT", appveyorSha(head));
    values.put("APPVEYOR_REPO_COMMIT", appveyorSha(build));
    assertEquals(
        merge ? "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" : null,
        new AppVeyorInfo(new CiEnvironmentImpl(values)).getMergeCommitSha());
  }

  private static String appveyorSha(String value) {
    if ("head".equals(value)) return SHA;
    if ("upper".equals(value)) return SHA.toUpperCase(java.util.Locale.ROOT);
    if ("build".equals(value)) return "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    return value;
  }

  @Test
  void readsCheckoutIdentityWhileKeepingCiWorkspace() {
    GitInfoProvider provider = mock(GitInfoProvider.class);
    when(provider.getGitInfo("/workspace/tools"))
        .thenReturn(
            new GitInfo("https://github.com/org/tools.git", "tools", null, new CommitInfo(SHA)));
    Map<String, String> tags =
        new CITagsProvider(provider)
            .getCiTags(
                CIInfo.builder(null).ciWorkspace("/workspace").build(),
                PullRequestInfo.EMPTY,
                "/workspace/tools");
    assertEquals("/workspace", tags.get("ci.workspace_path"));
    assertEquals(SHA, tags.get("git.commit.sha"));
    assertEquals("tools", tags.get("git.branch"));
    assertEquals("https://github.com/org/tools.git", tags.get("git.repository_url"));
    verify(provider, never()).getGitInfo("/workspace");
  }

  @Test
  void comparesPhysicalRootsWithoutTreatingNestedClonesAsAliases(@TempDir Path root)
      throws Exception {
    Path nested = Files.createDirectory(root.resolve("nested"));
    Path alias = Files.createSymbolicLink(root.resolve("alias"), nested);
    assertTrue(GitCheckoutInfo.samePath(nested.toString(), alias.toString()));
    assertTrue(
        GitCheckoutInfo.samePath(
            nested.toString(), nested.resolve("..").resolve("nested").toString()));
    assertFalse(GitCheckoutInfo.samePath(root.toString(), nested.toString()));
    assertFalse(GitCheckoutInfo.samePath(nested.toString(), root.resolve("missing").toString()));
    assertFalse(GitCheckoutInfo.samePath(nested.toString(), "invalid\0path"));
    assertFalse(GitCheckoutInfo.samePath(nested.toString(), null));
  }
}
