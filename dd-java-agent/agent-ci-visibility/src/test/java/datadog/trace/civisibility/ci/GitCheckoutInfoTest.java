package datadog.trace.civisibility.ci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tabletest.junit.TableTest;

class GitCheckoutInfoTest {
  private static final String SHA = "abcdef0123456789abcdef0123456789abcdef01";

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

  @TableTest({
    "Scenario         | root        | workspace  | ciWorkspace | custom",
    "same checkout    | /workspace  | /workspace | true        | false ",
    "nested checkout  | /workspace/ | /workspace | true        | false ",
    "custom checkout  | /tools      | /workspace | false       | true  ",
    "no CI workspace  | /workspace  |            | false       | false ",
    "no checkout root |             | /workspace | false       | false "
  })
  void comparesTheCheckoutRootWithTheCiWorkspace(
      String root, String workspace, boolean ciWorkspace, boolean custom) {
    GitCheckoutInfo checkout = new GitCheckoutInfo(root, workspace);
    assertEquals(ciWorkspace, checkout.isCiWorkspace());
    assertEquals(custom, checkout.isCustom());
  }

  @TableTest({
    "Scenario         | sha     | revisions    | known | matches",
    "matching         | SHA     | [OTHER, SHA] | true  | true   ",
    "case-insensitive | UPPER   | [SHA]        | true  | true   ",
    "mismatch         | SHA     | [OTHER]      | true  | false  ",
    "abbreviated      | abcdef0 | [abcdef0]    | true  | false  ",
    "no revisions     | SHA     | []           | false | false  ",
    "blank revisions  | SHA     | ['', '']     | false | false  ",
    "no SHA           |         | [SHA]        | true  | false  "
  })
  void matchesFullCiRevisions(String sha, List<String> revisions, boolean known, boolean matches) {
    GitCheckoutInfo checkout =
        new GitCheckoutInfo("/workspace", "/workspace", resolve(revisions).toArray(new String[0]));
    assertEquals(known, checkout.hasCiRevision());
    assertEquals(matches, checkout.matchesCiRevision(resolve(sha)));
  }

  private static List<String> resolve(List<String> values) {
    List<String> resolved = new ArrayList<>();
    for (String value : values) {
      resolved.add(resolve(value));
    }
    return resolved;
  }

  private static String resolve(String value) {
    if ("SHA".equals(value)) {
      return SHA;
    }
    if ("UPPER".equals(value)) {
      return SHA.toUpperCase(Locale.ROOT);
    }
    if ("OTHER".equals(value)) {
      return "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    }
    return value;
  }
}
