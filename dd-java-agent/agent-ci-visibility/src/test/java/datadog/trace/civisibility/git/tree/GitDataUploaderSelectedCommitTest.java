package datadog.trace.civisibility.git.tree;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class GitDataUploaderSelectedCommitTest {
  @Test
  void deferredUnshallowCanRecoverAMissingSelectedRevision() throws Exception {
    String selected = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    Config config = mock(Config.class);
    when(config.isCiVisibilityGitUnshallowDefer()).thenReturn(true);
    GitClient client = mock(GitClient.class);
    when(client.getLatestCommits(selected)).thenReturn(emptyList(), singletonList(selected));
    GitRepoUnshallow unshallow = mock(GitRepoUnshallow.class);
    when(unshallow.unshallow()).thenReturn(true);
    GitInfoProvider info = mock(GitInfoProvider.class);
    when(info.getGitInfo("/checkout"))
        .thenReturn(
            new GitInfo("https://example.com/repo.git", null, null, new CommitInfo(selected)));
    GitDataApi api = mock(GitDataApi.class);
    when(api.searchCommits("https://example.com/repo.git", singletonList(selected)))
        .thenReturn(singletonList(selected));
    GitDataUploaderImpl uploader =
        new GitDataUploaderImpl(
            config,
            mock(CiVisibilityMetricCollector.class),
            api,
            client,
            unshallow,
            info,
            "/checkout",
            "origin");

    uploader.startOrObserveGitDataUpload().get(5, TimeUnit.SECONDS);

    InOrder order = inOrder(client, unshallow, api);
    order.verify(client).getLatestCommits(selected);
    order.verify(unshallow).unshallow();
    order.verify(client).getLatestCommits(selected);
    order.verify(api).searchCommits("https://example.com/repo.git", singletonList(selected));
    verify(client, never()).getObjects(any(), any());
  }
}
