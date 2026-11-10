package datadog.trace.civisibility.git.tree;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

import datadog.communication.util.IOUtils;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tabletest.junit.TableTest;

class ShellGitClientParsingTest {
  @TempDir Path checkout;

  @TableTest({
    "Scenario               | includeOldRoot | expectedCommands",
    "excluded recent roots  | false          | 2               ",
    "excluded and old roots | true           | 3               "
  })
  void excludedAncestorsDoNotStartOneProcessPerCommit(boolean includeOldRoot, int expectedCommands)
      throws Exception {
    List<String> recentRoots = new ArrayList<>();
    for (int i = 1; i < 1000; i++) {
      recentRoots.add(String.format("%040x", i));
    }
    String oldRoot = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    List<String> includedRoots = new ArrayList<>(recentRoots);
    if (includeOldRoot) {
      includedRoots.add(oldRoot);
    }
    ShellGitClient client =
        new ShellGitClient(
            mock(CiVisibilityMetricCollector.class),
            checkout.toString(),
            "1 month ago",
            1000,
            10_000);
    AtomicInteger commands = new AtomicInteger();
    ShellCommandExecutor command =
        mock(
            ShellCommandExecutor.class,
            invocation -> {
              if (!"executeCommand".equals(invocation.getMethod().getName())) {
                return RETURNS_DEFAULTS.answer(invocation);
              }
              switch (commands.getAndIncrement()) {
                case 0:
                  return emptyList();
                case 1:
                  return recentRoots;
                default:
                  return singletonList(oldRoot);
              }
            });
    Field executor = ShellGitClient.class.getDeclaredField("commandExecutor");
    executor.setAccessible(true);
    executor.set(client, command);

    List<String> objects =
        client.getObjects(singletonList("ffffffffffffffffffffffffffffffffffffffff"), includedRoots);

    assertEquals(expectedCommands, commands.get());
    assertEquals(includeOldRoot ? singletonList(oldRoot) : emptyList(), objects);
  }

  @Test
  void enumeratesEveryTagOnTheSelectedCommit() throws Exception {
    Path fixture =
        Paths.get(getClass().getClassLoader().getResource("ci/git/with_pack/git").toURI());
    IOUtils.copyFolder(fixture, Files.createDirectories(checkout.resolve(".git")));
    ShellCommandExecutor command = new ShellCommandExecutor(checkout.toFile(), 10_000);
    command.executeCommand(IOUtils::readFully, "git", "config", "column.ui", "always");
    command.executeCommand(IOUtils::readFully, "git", "config", "column.tag", "always");
    ShellGitClient client =
        new ShellGitClient(
            mock(CiVisibilityMetricCollector.class),
            checkout.toString(),
            "1 month ago",
            1000,
            10_000);
    assertTrue(client.getTags(GitClient.HEAD).isEmpty());
    command.executeCommand(IOUtils::readFully, "git", "update-ref", "refs/tags/v1", GitClient.HEAD);
    assertEquals(singletonList("v1"), client.getTags(GitClient.HEAD));
    command.executeCommand(
        IOUtils::readFully, "git", "update-ref", "refs/tags/stable", GitClient.HEAD);
    assertEquals(2, client.getTags(GitClient.HEAD).size());
    assertTrue(client.getTags(GitClient.HEAD).contains("stable"));
    assertTrue(client.getTags(GitClient.HEAD).contains("v1"));
  }

  @Test
  void selectedCommitHistoryAndPackObjectsDoNotSubstituteHeadOrDiscardOldCommits()
      throws Exception {
    Path fixture =
        Paths.get(getClass().getClassLoader().getResource("ci/git/with_pack/git").toURI());
    IOUtils.copyFolder(fixture, Files.createDirectories(checkout.resolve(".git")));
    ShellGitClient client =
        new ShellGitClient(
            mock(CiVisibilityMetricCollector.class),
            checkout.toString(),
            "1 month ago",
            1000,
            10_000);
    String selected = "98cd7c8e9cf71e02dc28bd9b13928bee0f85b74c";
    String head = "5b6f3a6dab5972d73a56dff737bd08d995255c08";
    String parent =
        new ShellCommandExecutor(checkout.toFile(), 10_000)
            .executeCommand(IOUtils::readFully, "git", "rev-parse", selected + "^")
            .trim();

    assertEquals(selected, client.getLatestCommits(selected).get(0));
    assertFalse(client.getLatestCommits(selected).contains(head));
    List<String> objects = client.getObjects(emptyList(), singletonList(selected));
    assertTrue(objects.contains(selected));
    assertFalse(objects.contains(head));
    assertFalse(objects.contains(parent));
    assertTrue(client.getLatestCommits("--all").isEmpty());
  }

  @TableTest({
    "Scenario       | since        ",
    "inside window  | '1970-01-01' ",
    "outside window | '1 month ago'"
  })
  void selectedCommitHistoryUsesOneCanonicalSha(String since) throws Exception {
    Path fixture =
        Paths.get(getClass().getClassLoader().getResource("ci/git/with_pack/git").toURI());
    IOUtils.copyFolder(fixture, Files.createDirectories(checkout.resolve(".git")));
    ShellGitClient client =
        new ShellGitClient(
            mock(CiVisibilityMetricCollector.class), checkout.toString(), since, 1000, 10_000);
    String selected = "98cd7c8e9cf71e02dc28bd9b13928bee0f85b74c";

    List<String> commits = client.getLatestCommits(selected.toUpperCase(Locale.ROOT));

    assertEquals(client.getLatestCommits(selected), commits);
    assertEquals(selected, commits.get(0));
    assertEquals(1, commits.stream().filter(selected::equals).count());
  }

  @Test
  void oldSelectedObjectsRespectExcludedDescendantsWithoutAddingAncestors() throws Exception {
    Path fixture =
        Paths.get(getClass().getClassLoader().getResource("ci/git/with_pack/git").toURI());
    IOUtils.copyFolder(fixture, Files.createDirectories(checkout.resolve(".git")));
    ShellCommandExecutor command = new ShellCommandExecutor(checkout.toFile(), 10_000);
    ShellGitClient client =
        new ShellGitClient(
            mock(CiVisibilityMetricCollector.class),
            checkout.toString(),
            "1 month ago",
            1000,
            10_000);
    String selected = "98cd7c8e9cf71e02dc28bd9b13928bee0f85b74c";
    String head = command.executeCommand(IOUtils::readFully, "git", "rev-parse", "HEAD").trim();
    String parent =
        command.executeCommand(IOUtils::readFully, "git", "rev-parse", selected + "^").trim();
    assertTrue(client.getObjects(singletonList(head), singletonList(selected)).isEmpty());
    ShellGitClient allHistory =
        new ShellGitClient(
            mock(CiVisibilityMetricCollector.class),
            checkout.toString(),
            "1970-01-01",
            1000,
            10_000);
    assertTrue(allHistory.getObjects(singletonList(head), singletonList(selected)).isEmpty());

    String tree =
        command.executeCommand(IOUtils::readFully, "git", "rev-parse", "HEAD^{tree}").trim();
    String object =
        "tree "
            + tree
            + "\nauthor Test <test@example.com> 1 +0000\ncommitter Test <test@example.com> 1 +0000\n\nunrelated\n";
    String unrelated =
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
    List<String> objects = client.getObjects(singletonList(unrelated), singletonList(selected));
    assertTrue(objects.contains(selected));
    assertFalse(objects.contains(parent));
    assertFalse(objects.contains(head));
  }

  @Test
  void preservesSeparatorsInNamesAndMessagesAndSuppressesSignatureOutput() throws Exception {
    Path fixture =
        Paths.get(getClass().getClassLoader().getResource("ci/git/with_pack/git").toURI());
    Path gitDirectory = Files.createDirectories(checkout.resolve(".git"));
    IOUtils.copyFolder(fixture, gitDirectory);
    ShellCommandExecutor command = new ShellCommandExecutor(checkout.toFile(), 10_000);
    String tree =
        command.executeCommand(IOUtils::readFully, "git", "rev-parse", "HEAD^{tree}").trim();
    String author = "Do\",\"Jo";
    String message = "Update [\"a\",\"b\"]\n\nPreserve the complete message.";
    String object =
        "tree "
            + tree
            + "\nauthor "
            + author
            + " <author@example.com> 1 +0000\n"
            + "committer Committer <committer@example.com> 2 +0000\n\n"
            + message
            + "\n";
    String sha =
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
    command.executeCommand(IOUtils::readFully, "git", "config", "log.showSignature", "true");
    ShellGitClient client =
        new ShellGitClient(
            mock(CiVisibilityMetricCollector.class),
            checkout.toString(),
            "1 month ago",
            1000,
            10_000);

    CommitInfo info = client.getCommitInfo(sha, false);
    assertEquals(author, info.getAuthor().getName());
    assertEquals(message, info.getFullMessage());
    assertEquals("Tony Redondo", client.getCommitInfo(GitClient.HEAD, false).getAuthor().getName());
  }
}
