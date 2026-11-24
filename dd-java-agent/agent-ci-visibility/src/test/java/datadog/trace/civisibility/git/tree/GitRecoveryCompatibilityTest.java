package datadog.trace.civisibility.git.tree;

import static datadog.trace.civisibility.git.tree.GitTestRepository.GIT_COMMAND_TIMEOUT_MILLIS;
import static datadog.trace.civisibility.git.tree.GitTestRepository.client;
import static datadog.trace.civisibility.git.tree.GitTestRepository.fixture;
import static datadog.trace.civisibility.git.tree.GitTestRepository.git;
import static datadog.trace.civisibility.git.tree.GitTestRepository.replaceExecutor;
import static datadog.trace.civisibility.git.tree.GitTestRepository.selectedCommit;
import static datadog.trace.civisibility.git.tree.GitTestRepository.sha256Fixture;
import static datadog.trace.civisibility.git.tree.GitTestRepository.writeCommit;
import static datadog.trace.civisibility.git.tree.GitTestRepository.writeObject;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

class GitRecoveryCompatibilityTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void localDiffRemainsAvailableWithoutARemote(boolean workingTree) throws Exception {
    Path checkout = directory.resolve("checkout");
    ShellCommandExecutor command = fixture(checkout);
    String base = git(command, "rev-parse", "HEAD");
    git(command, "remote", "remove", "origin");
    assertEquals("", git(command, "remote"));
    Files.write(checkout.resolve("new.txt"), "local contents\n".getBytes(UTF_8));
    String blob = git(command, "hash-object", "-w", "new.txt");
    git(command, "read-tree", "--empty");
    git(command, "update-index", "--add", "--cacheinfo", "100644", blob, "new.txt");
    String selected = writeCommit(command, git(command, "write-tree"), base, "local change");
    git(command, "update-ref", "HEAD", selected);
    assertTrue(
        client(checkout, "1 month ago")
            .getGitDiff(base, workingTree ? null : selected)
            .contains("new.txt", 1, 1));
  }

  @Test
  void recoveryUsesTheCheckoutObjectFormat() throws Exception {
    Path origin = directory.resolve("origin");
    ShellCommandExecutor remote = sha256Fixture(origin);
    Path checkout = cloneFrom(remote, origin);
    String selected = selectedCommit(remote);
    git(remote, "update-ref", "refs/heads/selected", selected);
    ShellGitClient client = client(checkout, "1 month ago");
    client.fetchSelectedCommit(selected);
    assertEquals(64, selected.length());
    assertEquals(selected, client.getCommitInfo(selected, false).getSha());
    assertFalse(client.isShallow());
  }

  @TableTest({
    "Scenario                 | shallow | parentLocal | limit",
    "complete known parent    | false   | true        | 1000 ",
    "shallow known parent     | true    | true        | 1000 ",
    "bounded missing ancestry | false   | false       | 5    "
  })
  void backdatedRecoveryKeepsEligibleParents(boolean shallow, boolean parentLocal, int limit)
      throws Exception {
    Path origin = directory.resolve("origin");
    ShellCommandExecutor remote = fixture(origin);
    String original = git(remote, "rev-parse", "HEAD");
    String tree = git(remote, "rev-parse", "HEAD^{tree}");
    Path checkout = directory.resolve("checkout");
    if (!parentLocal) {
      git(remote, "clone", "--no-local", origin.toString(), checkout.toString());
    }
    String recent = original;
    for (int i = 0; i < (parentLocal ? 1 : 8); i++) {
      recent = writeCommit(remote, tree, recent, "recent parent " + i);
    }
    git(remote, "update-ref", "HEAD", recent);
    if (parentLocal) {
      if (shallow) {
        git(remote, "clone", "--depth=1", origin.toUri().toString(), checkout.toString());
      } else {
        git(remote, "clone", "--no-local", origin.toString(), checkout.toString());
      }
    }
    String selected =
        writeObject(
            remote,
            "tree "
                + tree
                + "\nparent "
                + recent
                + "\nauthor Test <test@example.com> 1 +0000"
                + "\ncommitter Test <test@example.com> 1 +0000\n\nbackdated selected\n");
    git(remote, "update-ref", "refs/heads/selected", selected);
    ShellCommandExecutor local =
        new ShellCommandExecutor(checkout.toFile(), GIT_COMMAND_TIMEOUT_MILLIS);
    String head = git(local, "rev-parse", "HEAD");
    String count = git(local, "rev-list", "--count", "HEAD");
    ShellGitClient client =
        new ShellGitClient(
            mock(CiVisibilityMetricCollector.class),
            checkout.toString(),
            "1 month ago",
            limit,
            GIT_COMMAND_TIMEOUT_MILLIS);
    client.fetchSelectedCommit(selected);
    List<String> commits = client.getLatestCommits(selected);
    assertEquals(selected, commits.get(0));
    assertTrue(commits.contains(recent));
    assertEquals(parentLocal ? 2 : limit, commits.size());
    assertTrue(client.getObjects(emptyList(), commits).contains(recent));
    assertEquals(shallow, client.isShallow());
    assertEquals(head, git(local, "rev-parse", "HEAD"));
    assertEquals(count, git(local, "rev-list", "--count", "HEAD"));
  }

  @Test
  void baseBranchFetchRemainsAvailableAfterPrivateRecovery() throws Exception {
    Path origin = directory.resolve("origin");
    ShellCommandExecutor remote = fixture(origin);
    String ancestor = git(remote, "rev-parse", "HEAD");
    String tree = git(remote, "rev-parse", "HEAD^{tree}");
    String head = writeCommit(remote, tree, ancestor, "recent checkout");
    git(remote, "update-ref", "HEAD", head);
    Path checkout = directory.resolve("checkout");
    git(remote, "clone", "--no-local", origin.toString(), checkout.toString());
    Path control = directory.resolve("control");
    git(remote, "clone", "--no-local", origin.toString(), control.toString());
    String selected = writeCommit(remote, tree, head, "selected child");
    git(remote, "update-ref", "refs/heads/selected", selected);
    git(
        new ShellCommandExecutor(checkout.toFile(), GIT_COMMAND_TIMEOUT_MILLIS),
        "update-ref",
        "refs/remotes/origin/base",
        ancestor);
    ShellGitClient client = client(checkout, "1 month ago");
    client.fetchSelectedCommit(selected);
    assertEquals(selected, client.getCommitInfo(selected, false).getSha());
    assertFalse(client.isShallow());
    assertEquals(ancestor, client.getBaseCommitSha("base", null));
    git(remote, "update-ref", "refs/heads/new-base", head);
    assertEquals(head, client(control, "1 month ago").getBaseCommitSha("new-base", null));
    assertEquals(head, client.getBaseCommitSha("new-base", null));
  }

  @Test
  @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
  void selectedRecoveryPreservesLegacyConfigurationBytes() throws Exception {
    Path origin = directory.resolve("origin");
    ShellCommandExecutor remote = fixture(origin);
    Path checkout = cloneFrom(remote, origin);
    String selected = selectedCommit(remote);
    Files.write(
        directory.resolve("legacy-upload.sh"),
        "#!/bin/sh\n[ \"$1\" = \"$(printf '\\351')\" ] || exit 42\nshift\nexec git-upload-pack \"$@\"\n"
            .getBytes(UTF_8));
    Files.write(
        checkout.resolve(".git/config"),
        "\n[remote \"origin\"]\n uploadpack = sh ../legacy-upload.sh é\n"
            .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
        java.nio.file.StandardOpenOption.APPEND);
    ShellGitClient client = client(checkout, "1 month ago");
    client.fetchSelectedCommit(selected);
    assertEquals(selected, client.getCommitInfo(selected, false).getSha());
  }

  @Test
  void unknownAbbreviationsAreRejectedBeforeRemoteRecovery() throws Exception {
    Path origin = directory.resolve("origin");
    ShellCommandExecutor remote = fixture(origin);
    Path checkout = cloneFrom(remote, origin);
    String selected = selectedCommit(remote);
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
              if ("rev-list".equals(arguments[6]) || "merge-base".equals(arguments[6])) {
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
    replaceExecutor(client, command);
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
    "Scenario                  | shallow | backdated",
    "complete checkout         | false   | false    ",
    "shallow checkout          | true    | false    ",
    "complete bounded fallback | false   | true     ",
    "shallow bounded fallback  | true    | true     "
  })
  void selectedRecoveryFiltersHistoricalBlobsUntilDiffNeedsThem(boolean shallow, boolean backdated)
      throws Exception {
    Path origin = directory.resolve("origin");
    ShellCommandExecutor remote = fixture(origin);
    git(remote, "config", "uploadpack.allowFilter", "true");
    Path checkout = directory.resolve("checkout");
    if (!shallow) {
      git(remote, "clone", "--no-local", origin.toString(), checkout.toString());
    }
    String parent = git(remote, "rev-parse", "HEAD");
    Files.write(origin.resolve("deleted.txt"), "historical contents\n".getBytes(UTF_8));
    String blob = git(remote, "hash-object", "-w", "deleted.txt");
    git(remote, "read-tree", "--empty");
    git(remote, "update-index", "--add", "--cacheinfo", "100644", blob, "deleted.txt");
    String historical = writeCommit(remote, git(remote, "write-tree"), parent, "historical file");
    git(remote, "read-tree", "--empty");
    String tree = git(remote, "write-tree");
    String selected =
        backdated
            ? writeObject(
                remote,
                "tree "
                    + tree
                    + "\nparent "
                    + historical
                    + "\nauthor Test <test@example.com> 1 +0000\ncommitter Test <test@example.com> 1 +0000\n\nbackdated tip\n")
            : writeCommit(remote, tree, historical, "deleted file");
    git(remote, "update-ref", "HEAD", selected);
    if (shallow) {
      git(remote, "clone", "--depth=1", origin.toUri().toString(), checkout.toString());
    }
    ShellCommandExecutor local =
        new ShellCommandExecutor(checkout.toFile(), GIT_COMMAND_TIMEOUT_MILLIS);
    git(local, "remote", "rename", "origin", "origin=a's");
    byte[] configuration = Files.readAllBytes(checkout.resolve(".git/config"));
    ShellGitClient client = client(checkout, backdated ? "1 month ago" : "1970-01-01T00:00:00Z");
    client.fetchSelectedCommit(selected);
    assertTrue(client.getLatestCommits(selected).contains(historical));
    assertFalse(
        git(local, "cat-file", "--batch-all-objects", "--batch-check=%(objectname)")
            .contains(blob));
    client = client(checkout, "1 month ago");
    client.getGitDiff(historical, selected);
    assertTrue(
        git(local, "cat-file", "--batch-all-objects", "--batch-check=%(objectname)")
            .contains(blob));
    assertEquals(shallow, client.isShallow());
    assertArrayEquals(configuration, Files.readAllBytes(checkout.resolve(".git/config")));
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
    ShellCommandExecutor local =
        new ShellCommandExecutor(checkout.toFile(), GIT_COMMAND_TIMEOUT_MILLIS);
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
