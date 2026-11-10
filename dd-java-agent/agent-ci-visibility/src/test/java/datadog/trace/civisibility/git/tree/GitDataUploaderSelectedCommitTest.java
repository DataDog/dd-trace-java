package datadog.trace.civisibility.git.tree;

import static java.nio.charset.StandardCharsets.UTF_8;
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

import datadog.communication.util.IOUtils;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.tabletest.junit.TableTest;

class GitDataUploaderSelectedCommitTest {
  @TempDir Path directory;

  @Test
  void fetchingAMissingChildStillRecoversEligibleAncestors() throws Exception {
    Path origin = Files.createDirectories(directory.resolve("origin"));
    Path fixture =
        Paths.get(getClass().getClassLoader().getResource("ci/git/with_pack/git").toURI());
    IOUtils.copyFolder(fixture, Files.createDirectories(origin.resolve(".git")));
    ShellCommandExecutor command = new ShellCommandExecutor(origin.toFile(), 10_000);
    String oldHead = command.executeCommand(IOUtils::readFully, "git", "rev-parse", "HEAD").trim();
    String tree =
        command.executeCommand(IOUtils::readFully, "git", "rev-parse", "HEAD^{tree}").trim();
    String ancestor = writeCommit(command, tree, oldHead, "eligible ancestor");
    String head = writeCommit(command, tree, ancestor, "shallow checkout");
    command.executeCommand(IOUtils::readFully, "git", "update-ref", "HEAD", head);
    Path checkout = directory.resolve("checkout");
    command.executeCommand(
        IOUtils::readFully,
        "git",
        "clone",
        "--depth=1",
        "--no-checkout",
        origin.toUri().toString(),
        checkout.toString());
    String selected = writeCommit(command, tree, head, "selected child");
    command.executeCommand(IOUtils::readFully, "git", "update-ref", "HEAD", selected);
    ShellGitClient client =
        new ShellGitClient(
            mock(CiVisibilityMetricCollector.class),
            checkout.toString(),
            "1 month ago",
            1000,
            10_000);
    assertTrue(client.getLatestCommits(selected).isEmpty());
    Config config = mock(Config.class);
    when(config.isCiVisibilityGitUnshallowDefer()).thenReturn(true);
    when(config.isCiVisibilityGitUnshallowEnabled()).thenReturn(true);
    GitInfoProvider info = mock(GitInfoProvider.class);
    when(info.getGitInfo(checkout.toString()))
        .thenReturn(
            new GitInfo("https://example.com/repo.git", null, null, new CommitInfo(selected)));
    GitDataApi api = mock(GitDataApi.class);
    when(api.searchCommits(eq("https://example.com/repo.git"), any())).thenReturn(emptyList());
    GitDataUploaderImpl uploader =
        new GitDataUploaderImpl(
            config,
            mock(CiVisibilityMetricCollector.class),
            api,
            client,
            new GitRepoUnshallow(config, client),
            info,
            checkout.toString(),
            "origin");

    uploader.startOrObserveGitDataUpload().get(5, TimeUnit.SECONDS);

    assertEquals(asList(selected, head, ancestor), client.getLatestCommits(selected));
    assertEquals(head, client.getSha(GitClient.HEAD));
    InOrder order = inOrder(api);
    order.verify(api).searchCommits("https://example.com/repo.git", asList(selected, head));
    order
        .verify(api)
        .searchCommits("https://example.com/repo.git", asList(selected, head, ancestor));
    verify(api).uploadPackFile(eq("https://example.com/repo.git"), eq(selected), any());
  }

  private static String writeCommit(
      ShellCommandExecutor command, String tree, String parent, String message) throws Exception {
    long now = System.currentTimeMillis() / 1000;
    String object =
        "tree "
            + tree
            + "\nparent "
            + parent
            + "\nauthor Test <test@example.com> "
            + now
            + " +0000\ncommitter Test <test@example.com> "
            + now
            + " +0000\n\n"
            + message
            + "\n";
    return command
        .executeCommand(
            IOUtils::readFully,
            object.getBytes(UTF_8),
            "git",
            "hash-object",
            "-t",
            "commit",
            "-w",
            "--stdin")
        .trim();
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
    Path origin = Files.createDirectories(directory.resolve("origin"));
    Path fixture =
        Paths.get(getClass().getClassLoader().getResource("ci/git/with_pack/git").toURI());
    IOUtils.copyFolder(fixture, Files.createDirectories(origin.resolve(".git")));
    ShellCommandExecutor command = new ShellCommandExecutor(origin.toFile(), 10_000);
    String oldHead = command.executeCommand(IOUtils::readFully, "git", "rev-parse", "HEAD").trim();
    String tree =
        command.executeCommand(IOUtils::readFully, "git", "rev-parse", "HEAD^{tree}").trim();
    long now = System.currentTimeMillis() / 1000;
    String object =
        "tree "
            + tree
            + "\nparent "
            + oldHead
            + "\nauthor Test <test@example.com> "
            + now
            + " +0000\ncommitter Test <test@example.com> "
            + now
            + " +0000\n\nrecent checkout\n";
    String head =
        command
            .executeCommand(
                IOUtils::readFully,
                object.getBytes(UTF_8),
                "git",
                "hash-object",
                "-t",
                "commit",
                "-w",
                "--stdin")
            .trim();
    command.executeCommand(IOUtils::readFully, "git", "update-ref", "HEAD", head);
    Path checkout = directory.resolve("checkout");
    command.executeCommand(
        IOUtils::readFully,
        "git",
        "clone",
        "--depth=1",
        "--no-checkout",
        origin.toUri().toString(),
        checkout.toString());
    ShellGitClient client =
        new ShellGitClient(
            mock(CiVisibilityMetricCollector.class),
            checkout.toString(),
            "1 month ago",
            1000,
            10_000);
    String selected = "98cd7c8e9cf71e02dc28bd9b13928bee0f85b74c";
    assertTrue(client.getLatestCommits(selected).isEmpty());
    Config config = mock(Config.class);
    when(config.isCiVisibilityGitUnshallowDefer()).thenReturn(deferred);
    when(config.isCiVisibilityGitUnshallowEnabled()).thenReturn(recoveryEnabled);
    GitInfoProvider info = mock(GitInfoProvider.class);
    when(info.getGitInfo(checkout.toString()))
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
            new GitRepoUnshallow(config, client),
            info,
            checkout.toString(),
            "origin");

    uploader.startOrObserveGitDataUpload().get(5, TimeUnit.SECONDS);

    assertEquals(head, client.getSha(GitClient.HEAD));
    if (recoveryEnabled) {
      assertEquals(singletonList(selected), client.getLatestCommits(selected));
      verify(api).searchCommits("https://example.com/repo.git", singletonList(selected));
    } else {
      assertTrue(client.getLatestCommits(selected).isEmpty());
      verify(api, never()).searchCommits(any(), any());
    }
  }

  @TableTest({
    "Scenario     | deferred",
    "deferred     | true    ",
    "not deferred | false   "
  })
  void recoversTheSelectedRevisionRatherThanOnlyHead(boolean deferred) throws Exception {
    String selected = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    Config config = mock(Config.class);
    when(config.isCiVisibilityGitUnshallowDefer()).thenReturn(deferred);
    when(config.isCiVisibilityGitUnshallowEnabled()).thenReturn(true);
    GitClient client = mock(GitClient.class);
    when(client.getLatestCommits(selected)).thenReturn(emptyList(), singletonList(selected));
    GitRepoUnshallow unshallow = mock(GitRepoUnshallow.class);
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
    if (!deferred) {
      order.verify(unshallow).unshallow();
    }
    order.verify(client).getLatestCommits(selected);
    order.verify(client).getCommitInfo(selected, true);
    order.verify(client).getLatestCommits(selected);
    order.verify(api).searchCommits("https://example.com/repo.git", singletonList(selected));
    verify(client, never()).getObjects(any(), any());
  }

  @Test
  void disabledRecoveryDoesNotFetchAMissingSelectedRevision() throws Exception {
    String selected = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    Config config = mock(Config.class);
    when(config.isCiVisibilityGitUnshallowDefer()).thenReturn(true);
    GitClient client = mock(GitClient.class);
    when(client.getLatestCommits(selected)).thenReturn(emptyList());
    GitInfoProvider info = mock(GitInfoProvider.class);
    when(info.getGitInfo("/checkout"))
        .thenReturn(
            new GitInfo("https://example.com/repo.git", null, null, new CommitInfo(selected)));
    GitDataApi api = mock(GitDataApi.class);
    GitRepoUnshallow unshallow = mock(GitRepoUnshallow.class);
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

    verify(client, never()).getCommitInfo(any(), anyBoolean());
    verify(unshallow, never()).unshallow();
    verify(api, never()).searchCommits(any(), any());
  }
}
