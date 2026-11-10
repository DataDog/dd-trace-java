package datadog.trace.civisibility.git.tree;

import static datadog.trace.civisibility.git.tree.GitTestRepository.client;
import static datadog.trace.civisibility.git.tree.GitTestRepository.fixture;
import static java.util.Collections.emptyList;
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
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.tabletest.junit.TableTest;

class GitDataUploaderSelectedCommitTest {
  private static final String URL = "https://example.com/repo.git";
  @TempDir Path directory;
  private final Config config = mock(Config.class);
  private final GitDataApi api = mock(GitDataApi.class);

  @TableTest({
    "Scenario        | abbreviated",
    "uppercase SHA   | false      ",
    "abbreviated SHA | true       "
  })
  void uploadedPackUsesTheCanonicalSelectedSha(boolean abbreviated) throws Exception {
    Path checkout = directory.resolve("checkout");
    fixture(checkout);
    ShellGitClient client = client(checkout, "1 month ago");
    String selected = "98cd7c8e9cf71e02dc28bd9b13928bee0f85b74c";
    String input = abbreviated ? selected.substring(0, 8) : selected.toUpperCase(Locale.ROOT);
    when(api.searchCommits(eq(URL), any())).thenReturn(emptyList());
    uploader(client, new GitRepoUnshallow(config, client), input, checkout.toString())
        .startOrObserveGitDataUpload()
        .get(5, TimeUnit.SECONDS);
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
