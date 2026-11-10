package datadog.trace.civisibility.git.tree;

import static datadog.trace.civisibility.git.tree.GitTestRepository.client;
import static datadog.trace.civisibility.git.tree.GitTestRepository.fixture;
import static datadog.trace.civisibility.git.tree.GitTestRepository.git;
import static datadog.trace.civisibility.git.tree.GitTestRepository.writeCommit;
import static datadog.trace.civisibility.git.tree.GitTestRepository.writeObject;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
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
    verify(api, times(2)).searchCommits(URL, asList(selected, head, ancestor));
    verify(api).uploadPackFile(eq(URL), eq(selected), any());
  }

  @TableTest({
    "Scenario          | deferred | recoveryEnabled",
    "deferred recovery | true     | true           ",
    "eager recovery    | false    | true           ",
    "disabled deferred | true     | false          ",
    "disabled eager    | false    | false          "
  })
  void alreadyPresentSelectedBoundaryRecoversItsOwnAncestors(
      boolean deferred, boolean recoveryEnabled) throws Exception {
    Path origin = directory.resolve("origin");
    ShellCommandExecutor command = fixture(origin);
    String tree = git(command, "rev-parse", "HEAD^{tree}");
    String base = git(command, "rev-parse", "HEAD");
    String head = writeCommit(command, tree, base, "checkout branch");
    git(command, "update-ref", "HEAD", head);
    Path checkout = shallowClone(command, origin);
    String ancestor = writeCommit(command, tree, base, "selected branch ancestor");
    String selected = writeCommit(command, tree, ancestor, "selected branch tip");
    git(command, "update-ref", "refs/heads/selected", selected);
    ShellCommandExecutor local = new ShellCommandExecutor(checkout.toFile(), 10_000);
    git(local, "fetch", "--depth=1", "--no-write-fetch-head", "origin", selected);
    ShellGitClient client = client(checkout, "1 month ago");
    assertEquals(singletonList(selected), client.getLatestCommits(selected));
    when(config.isCiVisibilityGitUnshallowDefer()).thenReturn(deferred);
    when(config.isCiVisibilityGitUnshallowEnabled()).thenReturn(recoveryEnabled);
    when(api.searchCommits(eq(URL), any())).thenReturn(emptyList());
    uploader(client, new GitRepoUnshallow(config, client), selected, checkout.toString())
        .startOrObserveGitDataUpload()
        .get(5, TimeUnit.SECONDS);
    assertEquals(
        recoveryEnabled ? asList(selected, ancestor) : singletonList(selected),
        client.getLatestCommits(selected));
    assertEquals(head, client.getSha(GitClient.HEAD));
    verify(api).uploadPackFile(eq(URL), eq(selected), any());
  }

  @TableTest({
    "Scenario         | deferred | since         | transportAllowed | worktree",
    "deferred         | true     | '1 month ago' | true             | false   ",
    "eager            | false    | '1 month ago' | true             | false   ",
    "full window      | true     | '1970-01-01'  | true             | false   ",
    "transport denied | true     | '1 month ago' | false            | false   ",
    "linked worktree  | true     | '1 month ago' | true             | true    "
  })
  void recoveryDoesNotMakeACompleteCheckoutShallow(
      boolean deferred, String since, boolean transportAllowed, boolean worktree) throws Exception {
    Path origin = directory.resolve("origin");
    ShellCommandExecutor command = fixture(origin);
    Path clone = directory.resolve("checkout");
    git(command, "clone", "--no-local", origin.toString(), clone.toString());
    Path checkout = worktree ? directory.resolve("linked checkout") : clone;
    if (worktree) {
      git(
          new ShellCommandExecutor(clone.toFile(), 10_000),
          "worktree",
          "add",
          "--detach",
          checkout.toString(),
          "HEAD");
    }
    ShellCommandExecutor local = new ShellCommandExecutor(checkout.toFile(), 10_000);
    String head = git(local, "rev-parse", "HEAD");
    String count = git(local, "rev-list", "--count", "HEAD");
    String tree = git(command, "rev-parse", "HEAD^{tree}");
    String selected =
        writeObject(
            command,
            "tree "
                + tree
                + "\nparent "
                + head
                + "\nauthor Test <test@example.com> 1 +0000\ncommitter Test <test@example.com> 1 +0000\n\nold remote revision\n");
    git(command, "update-ref", "refs/heads/old-selected", selected);
    ShellGitClient client = client(checkout, since);
    assertFalse(client.isShallow());
    when(config.isCiVisibilityGitUnshallowDefer()).thenReturn(deferred);
    when(config.isCiVisibilityGitUnshallowEnabled()).thenReturn(true);
    when(api.searchCommits(URL, singletonList(selected))).thenReturn(singletonList(selected));
    if (transportAllowed) {
      uploader(client, new GitRepoUnshallow(config, client), selected, checkout.toString())
          .startOrObserveGitDataUpload()
          .get(5, TimeUnit.SECONDS);
      assertEquals(selected, client.getLatestCommits(selected).get(0));
    } else {
      git(local, "config", "protocol.file.allow", "never");
      assertThrows(
          ExecutionException.class,
          () ->
              uploader(client, new GitRepoUnshallow(config, client), selected, checkout.toString())
                  .startOrObserveGitDataUpload()
                  .get(5, TimeUnit.SECONDS));
      assertTrue(client.getLatestCommits(selected).isEmpty());
    }
    assertEquals("false", git(local, "rev-parse", "--is-shallow-repository"));
    assertEquals(head, git(local, "rev-parse", "HEAD"));
    assertEquals(count, git(local, "rev-list", "--count", "HEAD"));
    assertFalse(client.isShallow());
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
    ShellGitClient client = spy(client(checkout, "1 month ago"));
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
      if (deferred) {
        verify(client).getCommitInfo(selected, true);
        assertEquals(
            "1",
            git(
                new ShellCommandExecutor(checkout.toFile(), 10_000),
                "rev-list",
                "--count",
                selected));
      } else {
        verify(client, never()).getCommitInfo(selected, true);
      }
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
