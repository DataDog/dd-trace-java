package datadog.trace.civisibility.git.tree;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.communication.util.IOUtils;
import datadog.trace.api.civisibility.telemetry.NoOpMetricCollector;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellGitClientEncodingTest {
  @TempDir Path directory;

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
