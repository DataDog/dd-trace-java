package datadog.trace.civisibility.git.tree;

import static datadog.trace.civisibility.git.tree.GitTestRepository.client;
import static datadog.trace.civisibility.git.tree.GitTestRepository.fixture;
import static datadog.trace.civisibility.git.tree.GitTestRepository.git;
import static datadog.trace.civisibility.git.tree.GitTestRepository.writeObject;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

import datadog.trace.api.git.CommitInfo;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tabletest.junit.TableTest;

class ShellGitClientParsingTest {
  @TempDir Path checkout;
  private ShellCommandExecutor command;
  private ShellGitClient client;

  @BeforeEach
  void setUp() throws Exception {
    command = fixture(checkout);
    client = client(checkout, "1 month ago");
  }

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
  void tagListingFailuresPropagateInsteadOfReturningNoTags() throws Exception {
    ShellCommandExecutor.ShellCommandFailedException failure =
        new ShellCommandExecutor.ShellCommandFailedException(129, "unsupported tag option");
    Field executor = ShellGitClient.class.getDeclaredField("commandExecutor");
    executor.setAccessible(true);
    executor.set(
        client,
        mock(
            ShellCommandExecutor.class,
            invocation -> {
              throw failure;
            }));
    assertEquals(
        failure,
        assertThrows(
            ShellCommandExecutor.ShellCommandFailedException.class,
            () -> client.getTags(GitClient.HEAD)));
  }

  @Test
  void enumeratesEveryTagOnTheSelectedCommit() throws Exception {
    git(command, "config", "column.ui", "always");
    git(command, "config", "column.tag", "always");
    assertTrue(client.getTags(GitClient.HEAD).isEmpty());
    git(command, "update-ref", "refs/tags/v1", GitClient.HEAD);
    assertEquals(singletonList("v1"), client.getTags(GitClient.HEAD));
    git(command, "update-ref", "refs/tags/stable", GitClient.HEAD);
    assertEquals(2, client.getTags(GitClient.HEAD).size());
    assertTrue(client.getTags(GitClient.HEAD).contains("stable"));
    assertTrue(client.getTags(GitClient.HEAD).contains("v1"));
  }

  @Test
  void selectedCommitHistoryAndPackObjectsDoNotSubstituteHeadOrDiscardOldCommits()
      throws Exception {
    String selected = "98cd7c8e9cf71e02dc28bd9b13928bee0f85b74c";
    String head = "5b6f3a6dab5972d73a56dff737bd08d995255c08";
    String parent = git(command, "rev-parse", selected + "^");

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
    client = client(checkout, since);
    String selected = "98cd7c8e9cf71e02dc28bd9b13928bee0f85b74c";

    List<String> commits = client.getLatestCommits(selected.toUpperCase(Locale.ROOT));

    assertEquals(client.getLatestCommits(selected), commits);
    assertEquals(selected, commits.get(0));
    assertEquals(1, commits.stream().filter(selected::equals).count());
  }

  @Test
  void oldSelectedObjectsRespectExcludedDescendantsWithoutAddingAncestors() throws Exception {
    String selected = "98cd7c8e9cf71e02dc28bd9b13928bee0f85b74c";
    String head = git(command, "rev-parse", "HEAD").trim();
    String parent = git(command, "rev-parse", selected + "^").trim();
    assertTrue(client.getObjects(singletonList(head), singletonList(selected)).isEmpty());
    ShellGitClient allHistory = client(checkout, "1970-01-01");
    assertTrue(allHistory.getObjects(singletonList(head), singletonList(selected)).isEmpty());

    String tree = git(command, "rev-parse", "HEAD^{tree}").trim();
    String object =
        "tree "
            + tree
            + "\nauthor Test <test@example.com> 1 +0000\ncommitter Test <test@example.com> 1 +0000\n\nunrelated\n";
    String unrelated = writeObject(command, object);
    List<String> objects = client.getObjects(singletonList(unrelated), singletonList(selected));
    assertTrue(objects.contains(selected));
    assertFalse(objects.contains(parent));
    assertFalse(objects.contains(head));
  }

  @Test
  void preservesSeparatorsInNamesAndMessagesAndSuppressesSignatureOutput() throws Exception {
    String tree = git(command, "rev-parse", "HEAD^{tree}").trim();
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
    String sha = writeObject(command, object);
    git(command, "config", "log.showSignature", "true");

    CommitInfo info = client.getCommitInfo(sha, false);
    assertEquals(author, info.getAuthor().getName());
    assertEquals(message, info.getFullMessage());
    assertEquals("Tony Redondo", client.getCommitInfo(GitClient.HEAD, false).getAuthor().getName());
  }
}
