package datadog.trace.civisibility.git.tree;

import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast;
import static datadog.environment.OperatingSystem.isWindows;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import datadog.communication.util.IOUtils;
import datadog.trace.api.civisibility.telemetry.NoOpMetricCollector;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.civisibility.diff.LineDiff;
import datadog.trace.civisibility.git.GitConfig;
import datadog.trace.civisibility.git.LocalFSGitInfoExtractor;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.tabletest.junit.TableTest;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ShellGitClientEncodingTest {
  private static final int GIT_COMMAND_TIMEOUT_MILLIS = 10_000;

  @TempDir Path directory;
  private String previousEncoding;
  private String previousFilesystemEncoding;
  private Charset actualFilesystemCharset;

  @BeforeEach
  void saveNativeEncoding() {
    previousEncoding = System.getProperty("native.encoding");
    previousFilesystemEncoding = System.getProperty("sun.jnu.encoding");
    actualFilesystemCharset =
        previousFilesystemEncoding == null
            ? Charset.defaultCharset()
            : Charset.forName(previousFilesystemEncoding);
  }

  @AfterEach
  void restoreNativeEncoding() {
    setEncoding("native.encoding", previousEncoding);
    setEncoding("sun.jnu.encoding", previousFilesystemEncoding);
  }

  private static void setEncoding(String property, String encoding) {
    if (encoding == null) {
      System.clearProperty(property);
    } else {
      System.setProperty(property, encoding);
    }
  }

  @TableTest({
    "Scenario               | Native encoding     | Filesystem encoding | Expected encoding",
    "UTF-8 filesystem       | UTF-8               | UTF-8               | UTF-8            ",
    "Latin-1 filesystem     | ISO-8859-1          | ISO-8859-1          | ISO-8859-1       ",
    "macOS with C locale    | US-ASCII            | UTF-8               | UTF-8            ",
    "filesystem wins        | UTF-8               | ISO-8859-1          | ISO-8859-1       ",
    "missing filesystem     | ISO-8859-1          |                     | ISO-8859-1       ",
    "unsupported filesystem | ISO-8859-1          | unsupported-charset | ISO-8859-1       ",
    "invalid filesystem     | ISO-8859-1          | invalid charset     | ISO-8859-1       ",
    "empty filesystem       | ISO-8859-1          | ''                  | ISO-8859-1       ",
    "JVM default fallback   | unsupported-charset |                     |                  "
  })
  void readsFilesystemPathsUsingGitPathEncoding(
      String nativeEncoding, String filesystemEncoding, String expectedEncoding) throws Exception {
    setEncoding("native.encoding", nativeEncoding);
    setEncoding("sun.jnu.encoding", filesystemEncoding);
    String pathname = directory + "/café";
    Charset outputCharset =
        isWindows()
            ? UTF_8
            : expectedEncoding == null
                ? Charset.defaultCharset()
                : Charset.forName(expectedEncoding);
    // The mocked output need not be representable by this JVM's actual filesystem charset.
    String expectedPathname = new String(pathname.getBytes(outputCharset), outputCharset);
    ShellGitClient client = clientWithOutput((pathname + "\n").getBytes(outputCharset));

    assertEquals(expectedPathname, client.getRepoRoot());
    assertEquals(expectedPathname, client.getGitFolder());

    String diff = "diff --git café.java café.java\n@@ -1 +1 @@\n-old\n+new\n";
    assertTrue(
        GitDiffParser.parse(new ByteArrayInputStream(diff.getBytes(outputCharset)))
            .contains(new String("café.java".getBytes(outputCharset), outputCharset), 1, 1));
  }

  @TableTest({
    "Scenario                     | Filesystem encoding | Filename encoding | Filename              | Expected filename    ",
    "UTF-8 filesystem             | UTF-8               | UTF-8             | CaféTest.java         | CaféTest.java        ",
    "UTF-8 fallback for ASCII     | US-ASCII            | UTF-8             | CaféTest.java         | CaféTest.java        ",
    "Latin-1 filesystem wins      | ISO-8859-1          | UTF-8             | CaféTest.java         | CafÃ©Test.java       ",
    "legacy Latin-1 names         | ISO-8859-1          | ISO-8859-1        | CaféTest.java         | CaféTest.java        ",
    "ambiguous EUC-JP bytes       | EUC-JP              | EUC-JP            | 息Test.java           | 息Test.java          ",
    "invalid bytes use filesystem | US-ASCII            | ISO-8859-1        | \u00f0\u009fTest.java | \uFFFD\uFFFDTest.java",
    "UTF-8 contains NEL byte      | UTF-8               | UTF-8             | ÅTest.java            | ÅTest.java           "
  })
  void readsDiffFilenamesIndependentOfFileContents(
      String filesystemEncoding, String filenameEncoding, String filename, String expectedFilename)
      throws Exception {
    Charset filenameCharset = Charset.forName(filenameEncoding);
    assumeTrue(
        !isWindows() || UTF_8.equals(Charset.forName(filesystemEncoding)),
        "Non-UTF-8 filesystem fixtures apply to Unix");
    System.setProperty("native.encoding", filesystemEncoding);
    System.setProperty("sun.jnu.encoding", filesystemEncoding);
    ByteArrayOutputStream diff = new ByteArrayOutputStream();
    diff.write(("diff --git " + filename + " " + filename + "\n").getBytes(filenameCharset));
    diff.write("@@ -1 +1 @@\n-old café\n+new café\n".getBytes(Charset.forName("ISO-8859-1")));

    assertTrue(
        GitDiffParser.parse(new ByteArrayInputStream(diff.toByteArray()))
            .contains(expectedFilename, 1, 1));
  }

  @Test
  void keepsHunksWithTheirFilesWhenUtf8ContainsNelBytes() throws Exception {
    System.setProperty("sun.jnu.encoding", "UTF-8");
    String patch =
        "diff --git ATest.java ATest.java\n@@ -1 +1 @@\n-old\n+new\n"
            + "diff --git ÅTest.java ÅTest.java\n@@ -2 +2 @@\n-old\n+new\n";
    LineDiff diff = GitDiffParser.parse(new ByteArrayInputStream(patch.getBytes(UTF_8)));

    assertTrue(diff.contains("ATest.java", 1, 1));
    assertFalse(diff.contains("ATest.java", 2, 2));
    assertTrue(diff.contains("ÅTest.java", 2, 2));
  }

  @TableTest({
    "Scenario     | Metadata encoding | Remote URL                            ",
    "UTF-8        | UTF-8             | https://example.com/café/東京/repo.git",
    "Windows-1252 | windows-1252      | https://example.com/équipe-€/repo.git "
  })
  void readsRemoteUrlsWithLegacyHostCharset(String metadataEncoding, String remoteUrl)
      throws Exception {
    // These properties exercise charset fallback, not native Windows process or filesystem APIs.
    System.setProperty("native.encoding", "windows-1252");
    System.setProperty("sun.jnu.encoding", "UTF-8");
    Charset charset = Charset.forName(metadataEncoding);
    ShellGitClient client = clientWithOutput((remoteUrl + "\n").getBytes(charset));
    Path config = directory.resolve("config");
    Files.write(config, ("[remote \"origin\"]\nurl = " + remoteUrl + "\n").getBytes(charset));

    assertEquals(remoteUrl, client.getRemoteUrl("origin"));
    assertEquals(remoteUrl, new GitConfig(config.toString()).getString("remote \"origin\"", "url"));
  }

  @Test
  void readsMixedEncodingConfigValuesIndependently() throws Exception {
    Charset legacyCharset = Charset.forName("windows-1252");
    System.setProperty("native.encoding", legacyCharset.name());
    String remoteUrl = "https://example.com/équipe/repo.git";
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bytes.write("# café\n[user]\nname = René\n".getBytes(legacyCharset));
    bytes.write(("[remote \"origin\"]\nurl = " + remoteUrl + "\n").getBytes(UTF_8));
    Path config = directory.resolve("config");
    Files.write(config, bytes.toByteArray());

    ShellCommandExecutor executor =
        new ShellCommandExecutor(directory.toFile(), GIT_COMMAND_TIMEOUT_MILLIS);
    String gitUrl =
        executor
            .executeCommand(
                input -> IOUtils.readFully(input, UTF_8),
                "git",
                "config",
                "--file",
                config.toString(),
                "--get",
                "remote.origin.url")
            .trim();
    GitConfig parsed = new GitConfig(config.toString());

    assertEquals(remoteUrl, gitUrl);
    assertEquals(remoteUrl, parsed.getString("remote \"origin\"", "url"));
    assertEquals("René", parsed.getString("user", "name"));
  }

  @TableTest({
    "Scenario                 | Native encoding | Filesystem encoding | Metadata encoding | Value   ",
    "UTF-8 host               | UTF-8           | UTF-8               | UTF-8             | café    ",
    "ASCII host               | US-ASCII        | US-ASCII            | UTF-8             | équipe  ",
    "Latin-1 host             | ISO-8859-1      | ISO-8859-1          | UTF-8             | équipe  ",
    "ASCII metadata           | US-ASCII        | US-ASCII            | US-ASCII          | team    ",
    "legacy Latin-1 metadata  | ISO-8859-1      | ISO-8859-1          | ISO-8859-1        | café    ",
    "legacy Windows metadata  | windows-1252    | windows-1252        | windows-1252      | équipe-€",
    "macOS with C locale      | US-ASCII        | UTF-8               | UTF-8             | équipe  ",
    "legacy text, UTF-8 paths | ISO-8859-1      | UTF-8               | ISO-8859-1        | café    "
  })
  void readsMetadataIndependentOfFilesystemCharset(
      String nativeEncoding, String filesystemEncoding, String metadataEncoding, String value)
      throws Exception {
    Charset outputCharset = Charset.forName(metadataEncoding);
    assumeTrue(
        !isWindows() || UTF_8.equals(outputCharset) || "US-ASCII".equals(metadataEncoding),
        "Legacy ref fixtures use Unix pathname bytes");
    System.setProperty("native.encoding", nativeEncoding);
    System.setProperty("sun.jnu.encoding", filesystemEncoding);
    ShellGitClient client = clientWithOutput((value + "\n").getBytes(outputCharset));

    assertEquals(value, client.getCurrentBranch());
    assertEquals(value, client.getRemoteUrl("origin"));
    assertEquals(singletonList(value), client.getTags("HEAD"));
    Charset commandCharset =
        isWindows()
            ? UTF_8
            : isJavaVersionAtLeast(18)
                ? Charset.forName(filesystemEncoding)
                : Charset.defaultCharset();
    String commandRef = new String(value.getBytes(outputCharset), commandCharset);
    assertEquals(commandRef, client.getRemoteName());
    assertEquals(commandRef, client.detectDefaultBranch("origin"));
    assertEquals(singletonList(commandRef), client.getBaseBranchCandidates(commandRef, "origin"));

    // Property overrides above simulate decoding; the JVM's filesystem charset is fixed at startup.
    Charset pathCharset = isWindows() ? UTF_8 : Charset.forName(filesystemEncoding);
    String nativeRef = new String(("refs/heads/" + value).getBytes(outputCharset), pathCharset);
    assumeTrue(
        isWindows() || actualFilesystemCharset.newEncoder().canEncode(nativeRef),
        "Fixture ref must be representable by this JVM's filesystem charset");

    ShellCommandExecutor executor =
        new ShellCommandExecutor(directory.toFile(), GIT_COMMAND_TIMEOUT_MILLIS);
    String sha = createCommit(executor);
    Path gitDirectory = directory.resolve(".git");
    Files.write(
        gitDirectory.resolve("HEAD"), ("ref: refs/heads/" + value + "\n").getBytes(outputCharset));
    Path config = gitDirectory.resolve("config");
    String remoteUrl = "https://example.com/" + value + "/repo.git";
    Files.write(config, ("[remote \"origin\"]\nurl = " + remoteUrl + "\n").getBytes(outputCharset));

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

    ShellGitClient client = newClient();
    Field executorField = ShellGitClient.class.getDeclaredField("commandExecutor");
    executorField.setAccessible(true);
    executorField.set(client, executor);
    return client;
  }

  @Test
  void preservesRefBytesForSubsequentGitCommands() throws Exception {
    Charset argumentCharset =
        isWindows() || isJavaVersionAtLeast(18)
            ? actualFilesystemCharset
            : Charset.defaultCharset();
    assumeTrue(
        argumentCharset.newEncoder().canEncode("équipe"),
        "Fixture refs must be representable as this JVM's process arguments");
    ShellCommandExecutor executor =
        new ShellCommandExecutor(directory.toFile(), GIT_COMMAND_TIMEOUT_MILLIS);
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

    ShellGitClient client = newClient();

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
    ShellCommandExecutor executor =
        new ShellCommandExecutor(directory.toFile(), GIT_COMMAND_TIMEOUT_MILLIS);
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

    ShellGitClient client = newClient();
    CommitInfo info = client.getCommitInfo(sha, false);

    assertEquals("René", info.getAuthor().getName());
    assertEquals("René", info.getCommitter().getName());
    assertEquals("café", info.getFullMessage());
    assertEquals(singletonList("édition"), client.getTags(sha));
  }

  @TableTest({
    "Scenario                  | Commit charset | Encoding header",
    "UTF-8 on a Latin-1 host   | UTF-8          |                ",
    "declared Latin-1 commit   | ISO-8859-1     | ISO-8859-1     ",
    "unlabelled Latin-1 commit | ISO-8859-1     |                "
  })
  void readsCommitMetadataOnLegacyHosts(String commitCharset, String encodingHeader)
      throws Exception {
    System.setProperty("native.encoding", "ISO-8859-1");
    ShellCommandExecutor executor =
        new ShellCommandExecutor(directory.toFile(), GIT_COMMAND_TIMEOUT_MILLIS);
    String sha = createCommit(executor, Charset.forName(commitCharset), encodingHeader);

    CommitInfo info = newClient().getCommitInfo(sha, false);

    assertEquals(sha, info.getSha());
    assertEquals("René", info.getAuthor().getName());
    assertEquals("René", info.getCommitter().getName());
    assertEquals("café", info.getFullMessage());
  }

  private ShellGitClient newClient() {
    return new ShellGitClient(
        NoOpMetricCollector.INSTANCE,
        directory.toString(),
        "1 day ago",
        10,
        GIT_COMMAND_TIMEOUT_MILLIS);
  }

  private String createCommit(ShellCommandExecutor executor) throws Exception {
    return createCommit(executor, UTF_8, null);
  }

  private String createCommit(ShellCommandExecutor executor, Charset charset, String encodingHeader)
      throws Exception {
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
            + "committer René <rene@example.com> 1700000000 +0000\n"
            + (encodingHeader == null ? "" : "encoding " + encodingHeader + "\n")
            + "\n"
            + "café\n";
    return executor
        .executeCommand(
            input -> IOUtils.readFully(input, UTF_8),
            commit.getBytes(charset),
            "git",
            "hash-object",
            "-t",
            "commit",
            "-w",
            "--stdin")
        .trim();
  }
}
