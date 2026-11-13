package datadog.trace.civisibility.git.tree;

import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast;
import static datadog.environment.OperatingSystem.isWindows;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import datadog.communication.util.IOUtils;
import datadog.trace.api.civisibility.telemetry.NoOpMetricCollector;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.civisibility.git.GitConfig;
import datadog.trace.civisibility.git.LocalFSGitInfoExtractor;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ShellGitClientEncodingTest {
  @TempDir Path directory;
  private String previousEncoding;

  @BeforeEach
  void saveNativeEncoding() {
    previousEncoding = System.getProperty("native.encoding");
  }

  @AfterEach
  void restoreNativeEncoding() {
    if (previousEncoding == null) {
      System.clearProperty("native.encoding");
    } else {
      System.setProperty("native.encoding", previousEncoding);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"UTF-8", "ISO-8859-1"})
  void readsFilesystemPathsUsingGitPathEncoding(String nativeEncoding) throws Exception {
    System.setProperty("native.encoding", nativeEncoding);
    String pathname = directory.resolve("café").toString();
    Charset outputCharset = isWindows() ? UTF_8 : Charset.forName(nativeEncoding);
    ShellGitClient client = clientWithOutput((pathname + "\n").getBytes(outputCharset));

    assertEquals(pathname, client.getRepoRoot());
    assertEquals(pathname, client.getGitFolder());

    String diff = "diff --git café.java café.java\n@@ -1 +1 @@\n-old\n+new\n";
    assertTrue(
        GitDiffParser.parse(new ByteArrayInputStream(diff.getBytes(outputCharset)))
            .contains("café.java", 1, 1));
  }

  @TableTest({
    "Scenario                | Native encoding | Metadata encoding | Value   ",
    "UTF-8 host              | UTF-8           | UTF-8             | café    ",
    "ASCII host              | US-ASCII        | UTF-8             | équipe  ",
    "Latin-1 host            | ISO-8859-1      | UTF-8             | équipe  ",
    "ASCII metadata          | US-ASCII        | US-ASCII          | team    ",
    "legacy Latin-1 metadata | ISO-8859-1      | ISO-8859-1        | café    ",
    "legacy Windows metadata | windows-1252    | windows-1252      | équipe-€"
  })
  void readsMetadataIndependentOfFilesystemCharset(
      String nativeEncoding, String metadataEncoding, String value) throws Exception {
    Charset outputCharset = Charset.forName(metadataEncoding);
    assumeTrue(
        !isWindows() || UTF_8.equals(outputCharset) || "US-ASCII".equals(metadataEncoding),
        "Git for Windows uses UTF-8 for local names and config");
    System.setProperty("native.encoding", nativeEncoding);
    ShellGitClient client = clientWithOutput((value + "\n").getBytes(outputCharset));

    assertEquals(value, client.getCurrentBranch());
    assertEquals(value, client.getRemoteUrl("origin"));
    assertEquals(Collections.singletonList(value), client.getTags("HEAD"));
    Charset commandCharset =
        isWindows()
            ? UTF_8
            : isJavaVersionAtLeast(18) ? Charset.forName(nativeEncoding) : Charset.defaultCharset();
    String commandRef = new String(value.getBytes(outputCharset), commandCharset);
    assertEquals(commandRef, client.getRemoteName());
    assertEquals(commandRef, client.detectDefaultBranch("origin"));
    assertEquals(
        Collections.singletonList(commandRef),
        client.getBaseBranchCandidates(commandRef, "origin"));

    ShellCommandExecutor executor = new ShellCommandExecutor(directory.toFile(), 10_000);
    String sha = createCommit(executor);
    Path gitDirectory = directory.resolve(".git");
    Files.write(
        gitDirectory.resolve("HEAD"), ("ref: refs/heads/" + value + "\n").getBytes(outputCharset));
    Path config = gitDirectory.resolve("config");
    String remoteUrl = "https://example.com/" + value + "/repo.git";
    Files.write(config, ("[remote \"origin\"]\nurl = " + remoteUrl + "\n").getBytes(outputCharset));

    // Simulate native path decoding without changing this JVM's filesystem charset.
    Charset pathCharset = isWindows() ? UTF_8 : Charset.forName(nativeEncoding);
    String nativeRef = new String(("refs/heads/" + value).getBytes(outputCharset), pathCharset);
    Path ref = gitDirectory.resolve(nativeRef);
    Files.createDirectories(ref.getParent());
    Files.write(ref, (sha + "\n").getBytes(UTF_8));

    assertEquals(remoteUrl, new GitConfig(config.toString()).getString("remote \"origin\"", "url"));
    GitInfo info = new LocalFSGitInfoExtractor().headCommit(gitDirectory.toString());
    assertEquals(value, info.getBranch());
    assertEquals(remoteUrl, info.getRepositoryURL());
    assertEquals(sha, info.getCommit().getSha());
  }

  private ShellGitClient clientWithOutput(byte[] output) throws Exception {
    ShellCommandExecutor executor = mock(ShellCommandExecutor.class);
    doAnswer(
            invocation -> {
              ShellCommandExecutor.OutputParser<?> parser = invocation.getArgument(0);
              return parser.parse(new ByteArrayInputStream(output));
            })
        .when(executor)
        .executeCommand(any(), (String[]) any());

    ShellGitClient client =
        new ShellGitClient(
            NoOpMetricCollector.INSTANCE, directory.toString(), "1 day ago", 10, 10_000);
    Field executorField = ShellGitClient.class.getDeclaredField("commandExecutor");
    executorField.setAccessible(true);
    executorField.set(client, executor);
    return client;
  }

  @Test
  void preservesRefBytesForSubsequentGitCommands() throws Exception {
    ShellCommandExecutor executor = new ShellCommandExecutor(directory.toFile(), 10_000);
    String sha = createCommit(executor);
    executor.executeCommand(
        ShellCommandExecutor.OutputParser.IGNORE,
        ("update refs/heads/équipe "
                + sha
                + "\n"
                + "update refs/remotes/équipe/main "
                + sha
                + "\n"
                + "update refs/remotes/équipe/release/été "
                + sha
                + "\n")
            .getBytes(UTF_8),
        "git",
        "update-ref",
        "--stdin");
    Files.write(directory.resolve(".git/HEAD"), "ref: refs/heads/équipe\n".getBytes(UTF_8));
    Files.write(
        directory.resolve(".git/config"),
        "[remote \"équipe\"]\nurl = https://example.com/repo.git\n".getBytes(UTF_8));

    ShellGitClient client =
        new ShellGitClient(
            NoOpMetricCollector.INSTANCE, directory.toString(), "1 day ago", 10, 10_000);

    assertEquals("équipe", client.getCurrentBranch());
    assertEquals(sha, client.getBaseCommitSha("main", null));
    List<String> candidates = client.getBaseBranchCandidates(null, client.getRemoteName());
    assertEquals(2, candidates.size());
    for (String candidate : candidates) {
      assertEquals(sha, client.getMergeBase(candidate, "HEAD"));
    }
  }

  @Test
  void readsUnicodeMetadataAndTagsWithConfiguredLegacyEncoding() throws Exception {
    ShellCommandExecutor executor = new ShellCommandExecutor(directory.toFile(), 10_000);
    String sha = createCommit(executor);
    executor.executeCommand(
        ShellCommandExecutor.OutputParser.IGNORE,
        "git",
        "config",
        "i18n.logOutputEncoding",
        "ISO-8859-1");
    executor.executeCommand(
        ShellCommandExecutor.OutputParser.IGNORE,
        ("update refs/tags/édition " + sha + "\n").getBytes(UTF_8),
        "git",
        "update-ref",
        "--stdin");

    ShellGitClient client =
        new ShellGitClient(
            NoOpMetricCollector.INSTANCE, directory.toString(), "1 day ago", 10, 10_000);
    CommitInfo info = client.getCommitInfo(sha, false);

    assertEquals("René", info.getAuthor().getName());
    assertEquals("René", info.getCommitter().getName());
    assertEquals("café", info.getFullMessage());
    assertEquals(Collections.singletonList("édition"), client.getTags(sha));
  }

  private String createCommit(ShellCommandExecutor executor) throws Exception {
    executor.executeCommand(ShellCommandExecutor.OutputParser.IGNORE, "git", "init", "--quiet");
    String tree =
        executor
            .executeCommand(
                input -> IOUtils.readFully(input, UTF_8),
                new byte[0],
                "git",
                "hash-object",
                "-t",
                "tree",
                "-w",
                "--stdin")
            .trim();
    String commit =
        "tree "
            + tree
            + "\n"
            + "author René <rene@example.com> 1700000000 +0000\n"
            + "committer René <rene@example.com> 1700000000 +0000\n\n"
            + "café\n";
    return executor
        .executeCommand(
            input -> IOUtils.readFully(input, UTF_8),
            commit.getBytes(UTF_8),
            "git",
            "hash-object",
            "-t",
            "commit",
            "-w",
            "--stdin")
        .trim();
  }
}
