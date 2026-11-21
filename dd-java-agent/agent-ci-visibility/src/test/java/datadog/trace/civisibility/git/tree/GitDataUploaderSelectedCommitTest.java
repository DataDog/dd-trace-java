package datadog.trace.civisibility.git.tree;

import static datadog.trace.civisibility.git.tree.GitTestRepository.OLD_COMMIT_SHA;
import static datadog.trace.civisibility.git.tree.GitTestRepository.client;
import static datadog.trace.civisibility.git.tree.GitTestRepository.fixture;
import static datadog.trace.civisibility.git.tree.GitTestRepository.git;
import static datadog.trace.civisibility.git.tree.GitTestRepository.selectedCommit;
import static datadog.trace.civisibility.git.tree.GitTestRepository.sha256Fixture;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.tabletest.junit.TableTest;

class GitDataUploaderSelectedCommitTest {
  private static final long UPLOAD_WAIT_SECONDS = 5;
  private static final String URL = "https://example.com/repo.git";
  @TempDir Path directory;
  private final Config config = mock(Config.class);
  private final GitDataApi api = mock(GitDataApi.class);

  @TableTest({
    "Scenario            | objectFormat | prefixLength",
    "uppercase SHA1      | sha1         | 0           ",
    "short SHA1          | sha1         | 8           ",
    "uppercase SHA256    | sha256       | 0           ",
    "short SHA256        | sha256       | 8           ",
    "40 character SHA256 | sha256       | 40          ",
    "48 character SHA256 | sha256       | 48          ",
    "63 character SHA256 | sha256       | 63          "
  })
  void uploadedPackUsesTheCanonicalSelectedSha(String objectFormat, int prefixLength)
      throws Exception {
    Path checkout = directory.resolve("checkout");
    boolean sha256 = "sha256".equals(objectFormat);
    ShellCommandExecutor command = sha256 ? sha256Fixture(checkout) : fixture(checkout);
    String head = git(command, "rev-parse", "HEAD");
    String selected = sha256 ? selectedCommit(command) : OLD_COMMIT_SHA;
    String input =
        prefixLength == 0 ? selected.toUpperCase(Locale.ROOT) : selected.substring(0, prefixLength);
    ShellGitClient client = client(checkout, "1 month ago");
    List<String> expected = sha256 ? asList(selected, head) : singletonList(selected);
    assertEquals(expected, client.getLatestCommits(input));
    when(api.searchCommits(eq(URL), any())).thenReturn(emptyList());
    uploader(client, new GitRepoUnshallow(config, client), input, checkout.toString())
        .startOrObserveGitDataUpload()
        .get(UPLOAD_WAIT_SECONDS, TimeUnit.SECONDS);
    verify(api).searchCommits(URL, expected);
    verify(api).uploadPackFile(eq(URL), eq(selected), any());
  }

  private GitDataUploaderImpl uploader(
      GitClient client, GitRepoUnshallow unshallow, String selected, String checkout) {
    GitInfoProvider info = mock(GitInfoProvider.class);
    when(info.getGitInfo(checkout))
        .thenReturn(new GitInfo(URL, null, null, new CommitInfo(selected)));
    return new GitDataUploaderImpl(
        config,
        mock(CiVisibilityMetricCollector.class),
        api,
        client,
        unshallow,
        info,
        checkout,
        "origin");
  }
}
