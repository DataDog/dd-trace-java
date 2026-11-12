package datadog.trace.civisibility.git.tree;

import static datadog.environment.OperatingSystem.isWindows;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import datadog.communication.util.IOUtils;
import datadog.trace.api.civisibility.telemetry.NoOpMetricCollector;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShellGitClientEncodingTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"UTF-8", "ISO-8859-1"})
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void readsFilesystemPathsUsingGitPathEncoding(String nativeEncoding) throws Exception {
    String previousEncoding = System.getProperty("native.encoding");
    try {
      System.setProperty("native.encoding", nativeEncoding);
      String pathname = directory.resolve("café").toString();
      Charset outputCharset = isWindows() ? UTF_8 : Charset.forName(nativeEncoding);
      byte[] output = (pathname + "\n").getBytes(outputCharset);
      ShellCommandExecutor executor = mock(ShellCommandExecutor.class);
      doAnswer(
              invocation -> {
                ShellCommandExecutor.OutputParser<String> parser = invocation.getArgument(0);
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

      assertEquals(pathname, client.getRepoRoot());
      assertEquals(pathname, client.getGitFolder());
    } finally {
      if (previousEncoding == null) {
        System.clearProperty("native.encoding");
      } else {
        System.setProperty("native.encoding", previousEncoding);
      }
    }
  }

  @Test
  void readsUnicodeMetadataAndTagsWithConfiguredLegacyEncoding() throws Exception {
    ShellCommandExecutor executor = new ShellCommandExecutor(directory.toFile(), 10_000);
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
    String sha =
        executor
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
    executor.executeCommand(
        ShellCommandExecutor.OutputParser.IGNORE,
        "git",
        "config",
        "i18n.logOutputEncoding",
        "ISO-8859-1");
    executor.executeCommand(
        ShellCommandExecutor.OutputParser.IGNORE, "git", "update-ref", "refs/tags/édition", sha);

    ShellGitClient client =
        new ShellGitClient(
            NoOpMetricCollector.INSTANCE, directory.toString(), "1 day ago", 10, 10_000);
    CommitInfo info = client.getCommitInfo(sha, false);

    assertEquals("René", info.getAuthor().getName());
    assertEquals("René", info.getCommitter().getName());
    assertEquals("café", info.getFullMessage());
    assertEquals(Collections.singletonList("édition"), client.getTags(sha));
  }
}
