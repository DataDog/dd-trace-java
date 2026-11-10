package datadog.trace.civisibility.git.tree;

import static datadog.trace.civisibility.git.tree.GitTestRepository.client;
import static datadog.trace.civisibility.git.tree.GitTestRepository.fixture;
import static datadog.trace.civisibility.git.tree.GitTestRepository.git;
import static datadog.trace.civisibility.git.tree.GitTestRepository.writeCommit;
import static datadog.trace.civisibility.git.tree.GitTestRepository.writeObject;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    "Scenario         | includeOldRoot | backendExclusions | expectedCommands",
    "excluded recent  | false          | true              | 2               ",
    "excluded and old | true           | true              | 2               ",
    "capped recent    | false          | false             | 1               ",
    "capped and old   | true           | false             | 1               "
  })
  void unrecoveredRootsDoNotStartOneProcessPerCommit(
      boolean includeOldRoot, boolean backendExclusions, int expectedCommands) throws Exception {
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
              if (commands.getAndIncrement() == 0 && backendExclusions) {
                return includedRoots;
              }
              return singletonList("object-hash");
            });
    Field executor = ShellGitClient.class.getDeclaredField("commandExecutor");
    executor.setAccessible(true);
    executor.set(client, command);

    List<String> objects =
        client.getObjects(
            backendExclusions
                ? singletonList("ffffffffffffffffffffffffffffffffffffffff")
                : emptyList(),
            includedRoots);

    assertEquals(expectedCommands, commands.get());
    assertEquals(singletonList("object-hash"), objects);
  }

  @TableTest({
    "Scenario                  | backendExclusions | split",
    "long first branch known   | true              | 1001 ",
    "long first branch unknown | false             | 1001 ",
    "balanced branches known   | true              | 501  ",
    "balanced branches unknown | false             | 501  ",
    "unequal branches known    | true              | 901  ",
    "unequal branches unknown  | false             | 901  "
  })
  void cappedTraversalIncludesABackdatedSelectedMerge(boolean backendExclusions, int split)
      throws Exception {
    String parent = git(command, "rev-parse", "HEAD");
    long now = System.currentTimeMillis() / 1000;
    StringBuilder input = new StringBuilder();
    for (int i = 1; i <= 1002; i++) {
      String from = i == 1 || i == split ? parent : ":" + (i - 1);
      input
          .append("commit refs/heads/capped\nmark :")
          .append(i)
          .append("\ncommitter Test <test@example.com> ")
          .append(i == 1002 ? now - 3600 : now + i)
          .append(" +0000\ndata 1\nx\nfrom ")
          .append(from)
          .append('\n');
      if (i == 1002) {
        input.append("merge :").append(split - 1).append("\n");
      }
      input.append('\n');
    }
    command.executeCommand(
        IOUtils::readFully, input.toString().getBytes(UTF_8), "git", "fast-import", "--quiet");
    String selected = git(command, "rev-parse", "refs/heads/capped");
    String known = git(command, "rev-parse", selected + "^");
    List<String> commits = client.getLatestCommits(selected);
    if (backendExclusions) {
      commits.remove(known);
    }
    List<String> objects =
        client.getObjects(backendExclusions ? singletonList(known) : emptyList(), commits);
    assertTrue(objects.contains(selected));
    if (!backendExclusions) {
      assertTrue(objects.containsAll(commits));
    }
    if (backendExclusions) {
      assertFalse(objects.contains(known));
    }
    List<String> types =
        command.executeCommand(
            IOUtils::readLines,
            String.join("\n", objects).getBytes(UTF_8),
            "git",
            "cat-file",
            "--batch-check=%(objecttype)");
    assertTrue(types.stream().filter("commit"::equals).count() <= 1000);
  }

  @TableTest({
    "Scenario   | objects",
    "history    | false  ",
    "pack roots | true   "
  })
  void cappedWalkDoesNotReadTheEntirePackedHistory(boolean objects) throws Exception {
    // Repacking on older Git requires the fixture's remote HEAD to resolve to an object.
    git(command, "update-ref", "refs/remotes/origin/HEAD", "HEAD");
    StringBuilder input = new StringBuilder();
    long now = System.currentTimeMillis() / 1000;
    for (int i = 1; i <= 4000; i++) {
      input
          .append("commit refs/heads/long\nmark :")
          .append(i)
          .append("\ncommitter Test <test@example.com> ")
          .append(now + i)
          .append(" +0000\ndata 1\nx\n");
      if (i > 1) {
        input.append("from :").append(i - 1).append('\n');
      }
      input.append('\n');
    }
    command.executeCommand(
        IOUtils::readFully, input.toString().getBytes(UTF_8), "git", "fast-import", "--quiet");
    String known =
        writeObject(
            command,
            "tree "
                + git(command, "rev-parse", "refs/heads/long^{tree}")
                + "\nauthor Test <test@example.com> 1 +0000\ncommitter Test <test@example.com> 1 +0000\n\nknown root\n");
    git(command, "update-ref", "refs/heads/known", known);
    git(command, "repack", "-ad");
    git(command, "config", "core.commitGraph", "false");
    String selected = git(command, "rev-parse", "refs/heads/long");
    Path trace = checkout.resolve("object-reads.trace");
    ShellGitClient bounded =
        new ShellGitClient(
            mock(CiVisibilityMetricCollector.class), checkout.toString(), "1 month ago", 1, 10_000);
    Field executor = ShellGitClient.class.getDeclaredField("commandExecutor");
    executor.setAccessible(true);
    executor.set(
        bounded,
        new ShellCommandExecutor(
            checkout.toFile(), 10_000, singletonMap("GIT_TRACE_PACK_ACCESS", trace.toString())));
    List<String> result =
        objects
            ? bounded.getObjects(singletonList(known), singletonList(selected))
            : bounded.getLatestCommits(selected);
    assertTrue(result.contains(selected));
    int reads = Files.readAllLines(trace).size();
    assertTrue(
        reads < 100, "A one-commit limit must not read thousands of packed ancestors: " + reads);
  }

  @TableTest({
    "Scenario              | mixed | backdated",
    "known descendant      | false | false    ",
    "mixed roots           | true  | false    ",
    "backdated mixed roots | true  | true     "
  })
  void backendDescendantsExcludeTransitiveAncestors(boolean mixed, boolean backdated)
      throws Exception {
    String tree = git(command, "rev-parse", "HEAD^{tree}");
    String ancestor = git(command, "rev-parse", "HEAD");
    String middle = writeCommit(command, tree, ancestor, "middle");
    String known = writeCommit(command, tree, middle, "known descendant");
    List<String> roots = new ArrayList<>();
    roots.add(ancestor);
    String unknownParent = known;
    if (backdated) {
      unknownParent = writeCommit(command, tree, ancestor, "unadvertised fork");
      unknownParent = writeCommit(command, tree, unknownParent, "unadvertised child");
      unknownParent = writeCommit(command, tree, unknownParent, "unadvertised grandchild");
    }
    String unknown = writeCommit(command, tree, unknownParent, "unknown child");
    if (mixed) {
      roots.add(unknown);
    }
    if (backdated) {
      String old =
          writeObject(
              command,
              "tree "
                  + tree
                  + "\nauthor Test <test@example.com> 1 +0000\n"
                  + "committer Test <test@example.com> 1 +0000\n\nold unrelated root\n");
      roots.add(old);
    }
    List<String> objects = client.getObjects(singletonList(known), roots);
    assertFalse(objects.contains(ancestor));
    assertFalse(objects.contains(middle));
    assertFalse(objects.contains(known));
    assertEquals(mixed, objects.contains(unknown));
    if (backdated) {
      assertTrue(objects.contains(roots.get(2)));
    }
    if (!mixed) {
      assertTrue(objects.isEmpty());
    }
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
    "Scenario         | headAlias | oldTip",
    "old selected SHA | false     | true  ",
    "old HEAD         | true      | true  ",
    "recent selected  | false     | false ",
    "recent HEAD      | true      | false "
  })
  void oldSelectedTipDoesNotHideRecentParents(boolean headAlias, boolean oldTip) throws Exception {
    String tree = git(command, "rev-parse", "HEAD^{tree}");
    String parent = writeCommit(command, tree, git(command, "rev-parse", "HEAD"), "recent parent");
    String selected =
        writeObject(
            command,
            "tree "
                + tree
                + "\nparent "
                + parent
                + "\nauthor Test <test@example.com> 1 +0000\ncommitter Test <test@example.com> 1 +0000\n\nold tip\n");
    if (!oldTip) {
      selected = writeCommit(command, tree, selected, "recent tip above old parent");
    }
    if (headAlias) {
      git(command, "update-ref", "HEAD", selected);
    }
    List<String> commits = client.getLatestCommits(headAlias ? GitClient.HEAD : selected);
    assertEquals(headAlias && oldTip ? singletonList(parent) : asList(selected, parent), commits);
    assertTrue(client.getObjects(emptyList(), commits).containsAll(commits));
    assertTrue(client.getObjects(singletonList(selected), commits).isEmpty());
  }

  @TableTest({
    "Scenario       | since        ",
    "inside window  | '1970-01-01' ",
    "outside window | '1 month ago'"
  })
  void selectedCommitHistoryUsesOneCanonicalSha(String since) throws Exception {
    client = client(checkout, since);
    String selected = "98cd7c8e9cf71e02dc28bd9b13928bee0f85b74c";

    for (String input : asList(selected.toUpperCase(Locale.ROOT), selected.substring(0, 8))) {
      List<String> commits = client.getLatestCommits(input);
      assertEquals(client.getLatestCommits(selected), commits);
      assertEquals(selected, commits.get(0));
      assertEquals(1, commits.stream().filter(selected::equals).count());
    }
  }

  @Test
  void optionLikeSelectedRevisionsCannotEnumerateOtherRefs() throws Exception {
    String selected =
        writeCommit(
            command,
            git(command, "rev-parse", "HEAD^{tree}"),
            git(command, "rev-parse", "HEAD"),
            "recent commit");
    git(command, "update-ref", "refs/heads/recent", selected);
    assertTrue(client.getLatestCommits("--all").isEmpty());
    assertEquals(CommitInfo.NOOP, client.getCommitInfo("--all", false));
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
