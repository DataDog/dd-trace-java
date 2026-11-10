package datadog.trace.civisibility.git.tree;

import static datadog.trace.civisibility.git.tree.GitTestRepository.client;
import static datadog.trace.civisibility.git.tree.GitTestRepository.fixture;
import static datadog.trace.civisibility.git.tree.GitTestRepository.git;
import static datadog.trace.civisibility.git.tree.GitTestRepository.writeCommit;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
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
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.tabletest.junit.TableTest;

class GitDataUploaderSelectedCommitTest {
  private static final String URL = "https://example.com/repo.git";
  @TempDir Path directory;
  private final Config config = mock(Config.class);
  private final GitDataApi api = mock(GitDataApi.class);

  @Test
  void fetchingAMissingChildStillRecoversEligibleAncestors() throws Exception {
    Path origin = directory.resolve("origin");
    ShellCommandExecutor command = fixture(origin);
    String tree = git(command, "rev-parse", "HEAD^{tree}");
    String ancestor =
        writeCommit(command, tree, git(command, "rev-parse", "HEAD"), "eligible ancestor");
    String head = writeCommit(command, tree, ancestor, "shallow checkout");
    git(command, "update-ref", "HEAD", head);
    Path checkout = shallowClone(command, origin);
    String selected = writeCommit(command, tree, head, "selected child");
    git(command, "update-ref", "HEAD", selected);
    ShellGitClient client = client(checkout, "1 month ago");
    assertTrue(client.getLatestCommits(selected).isEmpty());
    when(config.isCiVisibilityGitUnshallowDefer()).thenReturn(true);
    when(config.isCiVisibilityGitUnshallowEnabled()).thenReturn(true);
    when(api.searchCommits(eq(URL), any())).thenReturn(emptyList());

    uploader(client, new GitRepoUnshallow(config, client), selected, checkout.toString())
        .startOrObserveGitDataUpload()
        .get(5, TimeUnit.SECONDS);

    assertEquals(asList(selected, head, ancestor), client.getLatestCommits(selected));
    assertEquals(head, client.getSha(GitClient.HEAD));
    InOrder order = inOrder(api);
    order.verify(api).searchCommits(URL, asList(selected, head));
    order.verify(api).searchCommits(URL, asList(selected, head, ancestor));
    verify(api).uploadPackFile(eq(URL), eq(selected), any());
  }

  @TableTest({
    "Scenario          | deferred | recoveryEnabled",
    "deferred recovery | true     | true           ",
    "eager recovery    | false    | true           ",
    "disabled deferred | true     | false          ",
    "disabled eager    | false    | false          "
  })
  void shallowCheckoutRecoversAnOldSelectedRevisionOnlyWhenEnabled(
      boolean deferred, boolean recoveryEnabled) throws Exception {
    Path origin = directory.resolve("origin");
    ShellCommandExecutor command = fixture(origin);
    String head =
        writeCommit(
            command,
            git(command, "rev-parse", "HEAD^{tree}"),
            git(command, "rev-parse", "HEAD"),
            "recent checkout");
    git(command, "update-ref", "HEAD", head);
    Path checkout = shallowClone(command, origin);
    ShellGitClient client = client(checkout, "1 month ago");
    String selected = "98cd7c8e9cf71e02dc28bd9b13928bee0f85b74c";
    assertTrue(client.getLatestCommits(selected).isEmpty());
    when(config.isCiVisibilityGitUnshallowDefer()).thenReturn(deferred);
    when(config.isCiVisibilityGitUnshallowEnabled()).thenReturn(recoveryEnabled);
    when(api.searchCommits(URL, singletonList(selected))).thenReturn(singletonList(selected));

    uploader(client, new GitRepoUnshallow(config, client), selected, checkout.toString())
        .startOrObserveGitDataUpload()
        .get(5, TimeUnit.SECONDS);

    assertEquals(head, client.getSha(GitClient.HEAD));
    if (recoveryEnabled) {
      assertEquals(singletonList(selected), client.getLatestCommits(selected));
      verify(api).searchCommits(URL, singletonList(selected));
    } else {
      assertTrue(client.getLatestCommits(selected).isEmpty());
      verify(api, never()).searchCommits(any(), any());
    }
  }

  @TableTest({
    "Scenario          | deferred | recoveryEnabled",
    "deferred          | true     | true           ",
    "not deferred      | false    | true           ",
    "disabled recovery | true     | false          "
  })
  void recoversTheSelectedRevisionRatherThanOnlyHead(boolean deferred, boolean recoveryEnabled)
      throws Exception {
    String selected = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    when(config.isCiVisibilityGitUnshallowDefer()).thenReturn(deferred);
    when(config.isCiVisibilityGitUnshallowEnabled()).thenReturn(recoveryEnabled);
    GitClient client = mock(GitClient.class);
    when(client.getLatestCommits(selected)).thenReturn(emptyList(), singletonList(selected));
    GitRepoUnshallow unshallow = mock(GitRepoUnshallow.class);
    when(api.searchCommits(URL, singletonList(selected))).thenReturn(singletonList(selected));

    uploader(client, unshallow, selected, "/checkout")
        .startOrObserveGitDataUpload()
        .get(5, TimeUnit.SECONDS);

    if (recoveryEnabled) {
      InOrder order = inOrder(client, unshallow, api);
      if (!deferred) {
        order.verify(unshallow).unshallow();
      }
      order.verify(client).getLatestCommits(selected);
      order.verify(client).getCommitInfo(selected, true);
      order.verify(client).getLatestCommits(selected);
      order.verify(api).searchCommits(URL, singletonList(selected));
    } else {
      verify(client, never()).getCommitInfo(any(), anyBoolean());
      verify(unshallow, never()).unshallow();
      verify(api, never()).searchCommits(any(), any());
    }
    verify(client, never()).getObjects(any(), any());
  }

  private Path shallowClone(ShellCommandExecutor command, Path origin) throws Exception {
    Path checkout = directory.resolve("checkout");
    git(
        command,
        "clone",
        "--depth=1",
        "--no-checkout",
        origin.toUri().toString(),
        checkout.toString());
    return checkout;
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
