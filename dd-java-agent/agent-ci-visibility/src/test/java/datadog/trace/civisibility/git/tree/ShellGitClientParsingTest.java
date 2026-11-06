package datadog.trace.civisibility.git.tree;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import datadog.communication.util.IOUtils;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellGitClientParsingTest {
  @TempDir Path checkout;

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

    assertEquals(selected, client.getLatestCommits(selected).get(0));
    assertFalse(client.getLatestCommits(selected).contains(head));
    assertTrue(client.getObjects(emptyList(), singletonList(selected)).contains(selected));
    assertFalse(client.getObjects(emptyList(), singletonList(selected)).contains(head));
    assertTrue(client.getLatestCommits("--all").isEmpty());
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
