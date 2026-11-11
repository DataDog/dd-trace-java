package datadog.trace.civisibility.git.tree;

import static datadog.trace.civisibility.git.tree.GitTestRepository.client;
import static datadog.trace.civisibility.git.tree.GitTestRepository.fixture;
import static datadog.trace.civisibility.git.tree.GitTestRepository.git;
import static datadog.trace.civisibility.git.tree.GitTestRepository.writeCommit;
import static datadog.trace.civisibility.git.tree.GitTestRepository.writeObject;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import datadog.communication.util.IOUtils;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tabletest.junit.TableTest;

class GitRecoveryCompatibilityTest {
  @TempDir Path directory;

  @Test
  void recoveryUsesTheCheckoutObjectFormat() throws Exception {
    Path origin = Files.createDirectory(directory.resolve("origin"));
    ShellCommandExecutor remote = new ShellCommandExecutor(origin.toFile(), 10_000);
    git(remote, "init", "--object-format=sha256");
    String tree =
        remote
            .executeCommand(
                IOUtils::readFully,
                new byte[0],
                "git",
                "hash-object",
                "-w",
                "-t",
                "tree",
                "--stdin")
            .trim();
    String person = "Test <test@example.com> " + System.currentTimeMillis() / 1000 + " +0000";
    String head =
        writeObject(
            remote, "tree " + tree + "\nauthor " + person + "\ncommitter " + person + "\n\nhead\n");
    git(remote, "update-ref", "HEAD", head);
    Path checkout = cloneFrom(remote, origin);
    String selected = writeCommit(remote, tree, head, "selected");
    git(remote, "update-ref", "refs/heads/selected", selected);
    ShellGitClient client = client(checkout, "1 month ago");
    client.fetchSelectedCommit(selected);
    assertEquals(64, selected.length());
    assertEquals(selected, client.getCommitInfo(selected, false).getSha());
    assertFalse(client.isShallow());
  }

  @Test
  void unknownAbbreviationsAreRejectedBeforeRemoteRecovery() throws Exception {
    Path origin = directory.resolve("origin");
    ShellCommandExecutor remote = fixture(origin);
    Path checkout = cloneFrom(remote, origin);
    String selected =
        writeCommit(
            remote,
            git(remote, "rev-parse", "HEAD^{tree}"),
            git(remote, "rev-parse", "HEAD"),
            "selected");
    git(remote, "update-ref", "refs/heads/selected", selected);
    ShellGitClient client = client(checkout, "1 month ago");
    IOException error =
        assertThrows(IOException.class, () -> client.fetchSelectedCommit(selected.substring(0, 8)));
    assertTrue(error.getMessage().contains("abbreviated"));
    client.fetchSelectedCommit(selected);
    assertEquals(selected, client.getCommitInfo(selected, false).getSha());
  }

  @TableTest({
    "Scenario                  | selectedRecoveryFirst | freshClient | shallow",
    "PR metadata first         | false                 | false       | false  ",
    "bounded recovery first    | true                  | false       | false  ",
    "fresh after recovery      | true                  | true        | false  ",
    "shallow PR metadata first | false                 | false       | true   ",
    "shallow recovery first    | true                  | false       | true   ",
    "fresh shallow recovery    | true                  | true        | true   "
  })
  void pullRequestMetadataFetchRetainsAncestorsOutsideTheUploadWindow(
      boolean selectedRecoveryFirst, boolean freshClient, boolean shallow) throws Exception {
    Path origin = directory.resolve("origin");
    ShellCommandExecutor remote = fixture(origin);
    Path checkout = directory.resolve("checkout");
    if (shallow) {
      git(remote, "clone", "--depth=1", origin.toUri().toString(), checkout.toString());
    } else {
      git(remote, "clone", "--no-local", origin.toString(), checkout.toString());
    }
    String base = git(remote, "rev-parse", "HEAD");
    String metadata = git(remote, "rev-parse", "HEAD^");
    String tree = git(remote, "rev-parse", "HEAD^{tree}");
    String oldParent =
        writeObject(
            remote,
            "tree "
                + tree
                + "\nparent "
                + base
                + "\nauthor Test <test@example.com> 1 +0000\ncommitter Test <test@example.com> 1 +0000\n\nold ancestor\n");
    String selected = writeCommit(remote, tree, oldParent, "PR source");
    git(remote, "update-ref", "refs/heads/selected", selected);
    ShellGitClient client = client(checkout, "1 month ago");
    if (selectedRecoveryFirst) {
      client.fetchSelectedCommit(selected);
      assertEquals(metadata, client.getCommitInfo(metadata, true).getSha());
      assertTrue(client.getLatestCommits(selected).contains(selected));
    }
    if (freshClient) {
      client = client(checkout, "1 month ago");
    }
    assertEquals(selected, client.getCommitInfo(selected, true).getSha());
    assertEquals(base, client.getMergeBase(base, selected));
    if (!selectedRecoveryFirst) {
      client.fetchSelectedCommit(selected);
      assertEquals(base, client.getMergeBase(base, selected));
    }
    assertEquals(shallow, client.isShallow());
  }

  @TableTest({
    "Scenario                          | interruption | fetch | present | missingParent | commandFailure",
    "timeout                           | false        | false | true    | false         | false         ",
    "interruption                      | true         | false | true    | false         | false         ",
    "fetch timeout                     | false        | true  | true    | false         | false         ",
    "absent fetch timeout              | false        | true  | false   | false         | false         ",
    "fetch interruption                | true         | true  | true    | false         | false         ",
    "absent fetch interruption         | true         | true  | false   | false         | false         ",
    "missing parent check timeout      | false        | false | true    | true          | false         ",
    "missing parent fetch timeout      | false        | true  | true    | true          | false         ",
    "missing parent fetch failure      | false        | true  | true    | true          | true          ",
    "missing parent check interruption | true         | false | true    | true          | false         ",
    "missing parent fetch interruption | true         | true  | true    | true          | false         "
  })
  void ancestryCheckDoesNotDiscardLocalMetadataOnTimeout(
      boolean interruption,
      boolean fetch,
      boolean present,
      boolean missingParent,
      boolean commandFailure)
      throws Exception {
    Path checkout = directory.resolve("checkout");
    ShellCommandExecutor command = spy(fixture(checkout));
    String selected =
        missingParent
            ? writeCommit(
                command,
                git(command, "rev-parse", "HEAD^{tree}"),
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "local metadata")
            : present
                ? git(command, "rev-parse", "HEAD")
                : "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    Exception failure =
        interruption
            ? new InterruptedException("cancelled")
            : commandFailure
                ? new ShellCommandExecutor.ShellCommandFailedException(128, "fetch failed")
                : new TimeoutException("ancestry check");
    AtomicInteger commands = new AtomicInteger();
    doAnswer(
            invocation -> {
              commands.incrementAndGet();
              Object[] arguments = invocation.getArguments();
              if ("rev-list".equals(arguments[4]) || "merge-base".equals(arguments[4])) {
                throw fetch
                    ? new ShellCommandExecutor.ShellCommandFailedException(128, "missing ancestry")
                    : failure;
              }
              return invocation.callRealMethod();
            })
        .when(command)
        .executeCommand(any(), (String[]) any());
    ShellGitClient client = spy(client(checkout, "1 month ago"));
    doThrow(failure).when(client).getRemoteName();
    Field executor = ShellGitClient.class.getDeclaredField("commandExecutor");
    executor.setAccessible(true);
    executor.set(client, command);
    if (!present) {
      Class<? extends Exception> failureType = failure.getClass();
      assertEquals(failure, assertThrows(failureType, () -> client.getCommitInfo(selected, true)));
    } else {
      CommitInfo info = client.getCommitInfo(selected, true);
      assertEquals(2, commands.get());
      assertEquals(selected, info.getSha());
      assertEquals(info, client.getCommitInfo(selected, false));
      if (missingParent) {
        assertTrue(info.isComplete());
        assertEquals("Test", info.getAuthor().getName());
        assertEquals(info.getAuthor(), info.getCommitter());
        assertEquals("local metadata", info.getFullMessage());
      }
      if (commandFailure) {
        assertNull(client.getMergeBase(GitClient.HEAD, selected));
      } else {
        assertEquals(
            failure,
            assertThrows(failure.getClass(), () -> client.getMergeBase(GitClient.HEAD, selected)));
      }
    }
  }

  @TableTest({
    "Scenario                  | shallow",
    "complete checkout         | false  ",
    "complete shallow recovery | true   "
  })
  void privateRecoveryRetainsBlobsForSubsequentDiffs(boolean shallow) throws Exception {
    Path origin = directory.resolve("origin");
    ShellCommandExecutor remote = fixture(origin);
    git(remote, "config", "uploadpack.allowFilter", "true");
    Path checkout = shallow ? directory.resolve("checkout") : cloneFrom(remote, origin);
    if (shallow) {
      git(remote, "clone", "--depth=1", origin.toUri().toString(), checkout.toString());
    }
    String base = git(remote, "rev-parse", "HEAD");
    Files.write(origin.resolve("new.txt"), "selected contents\n".getBytes(UTF_8));
    String blob = git(remote, "hash-object", "-w", "--no-filters", "new.txt");
    git(remote, "read-tree", "--empty");
    git(remote, "update-index", "--add", "--cacheinfo", "100644", blob, "new.txt");
    String tree = git(remote, "write-tree");
    String selected = writeCommit(remote, tree, base, "new file");
    git(remote, "update-ref", "refs/heads/selected", selected);
    ShellGitClient client = client(checkout, shallow ? "1970-01-01T00:00:00Z" : "1 month ago");
    client.fetchSelectedCommit(selected);
    assertTrue(client.getGitDiff(base, selected).contains("new.txt", 1, 1));
    assertEquals(shallow, client.isShallow());
    if (shallow) {
      assertEquals(
          Math.min(1000, Integer.parseInt(git(remote, "rev-list", "--count", selected))),
          client.getLatestCommits(selected).size());
    }
  }

  @TableTest({
    "Scenario             | originName              | rawPath",
    "quoted subsection    | origin's repository     | false  ",
    "equals in subsection | origin=a=b's repository | false  ",
    "UTF-8 subsection     | répo=a=b's repository   | true   "
  })
  void evaluatedConfigurationPreservesMultipleQuotedValues(String originName, boolean rawPath)
      throws Exception {
    Path origin = directory.resolve(originName);
    ShellCommandExecutor remote = fixture(origin);
    Path checkout = cloneFrom(remote, origin);
    ShellCommandExecutor local = new ShellCommandExecutor(checkout.toFile(), 10_000);
    String alias = "url." + (rawPath ? origin : origin.toUri()) + ".insteadOf";
    git(local, "config", "--add", alias, "first:");
    git(local, "config", "--add", alias, "second:");
    git(local, "config", "remote.origin.url", "second:");
    String selected =
        writeCommit(
            remote,
            git(remote, "rev-parse", "HEAD^{tree}"),
            git(remote, "rev-parse", "HEAD"),
            "selected");
    git(remote, "update-ref", "refs/heads/selected", selected);
    ShellGitClient client = client(checkout, "1 month ago");
    client.fetchSelectedCommit(selected);
    assertEquals(selected, client.getCommitInfo(selected, false).getSha());
    assertFalse(client.isShallow());
  }

  private Path cloneFrom(ShellCommandExecutor remote, Path origin) throws Exception {
    Path checkout = directory.resolve("checkout");
    git(remote, "clone", "--no-local", origin.toString(), checkout.toString());
    return checkout;
  }
}
