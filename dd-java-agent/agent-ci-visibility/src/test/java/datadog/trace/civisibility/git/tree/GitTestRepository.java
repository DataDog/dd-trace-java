package datadog.trace.civisibility.git.tree;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.mock;

import datadog.communication.util.IOUtils;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

public final class GitTestRepository {
  public static final long GIT_COMMAND_TIMEOUT_MILLIS = 10_000;
  static final String OLD_COMMIT_SHA = "98cd7c8e9cf71e02dc28bd9b13928bee0f85b74c";

  private GitTestRepository() {}

  static ShellCommandExecutor sha256Fixture(Path checkout) throws Exception {
    Files.createDirectories(checkout);
    ShellCommandExecutor command =
        new ShellCommandExecutor(checkout.toFile(), GIT_COMMAND_TIMEOUT_MILLIS);
    git(command, "init", "--object-format=sha256");
    String tree =
        command
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
            command,
            "tree " + tree + "\nauthor " + person + "\ncommitter " + person + "\n\nhead\n");
    git(command, "update-ref", "HEAD", head);
    return command;
  }

  static String selectedCommit(ShellCommandExecutor command) throws Exception {
    String selected =
        writeCommit(
            command,
            git(command, "rev-parse", "HEAD^{tree}"),
            git(command, "rev-parse", "HEAD"),
            "selected");
    git(command, "update-ref", "refs/heads/selected", selected);
    return selected;
  }

  static void replaceExecutor(ShellGitClient client, ShellCommandExecutor executor)
      throws Exception {
    java.lang.reflect.Field field = ShellGitClient.class.getDeclaredField("commandExecutor");
    field.setAccessible(true);
    field.set(client, executor);
  }

  static ShellCommandExecutor fixture(Path checkout) throws Exception {
    Path fixture = Paths.get(GitTestRepository.class.getResource("/ci/git/with_pack/git").toURI());
    IOUtils.copyFolder(fixture, Files.createDirectories(checkout.resolve(".git")));
    return new ShellCommandExecutor(checkout.toFile(), GIT_COMMAND_TIMEOUT_MILLIS);
  }

  static String git(ShellCommandExecutor command, String... args) throws Exception {
    String[] gitCommand = Stream.concat(Stream.of("git"), Stream.of(args)).toArray(String[]::new);
    return command.executeCommand(IOUtils::readFully, gitCommand).trim();
  }

  static ShellGitClient client(Path checkout, String since) {
    return new ShellGitClient(
        mock(CiVisibilityMetricCollector.class),
        checkout.toString(),
        since,
        1000,
        GIT_COMMAND_TIMEOUT_MILLIS);
  }

  static String writeObject(ShellCommandExecutor command, String object) throws Exception {
    String[] gitCommand = {"git", "hash-object", "-t", "commit", "-w", "--stdin"};
    return command.executeCommand(IOUtils::readFully, object.getBytes(UTF_8), gitCommand).trim();
  }

  static String writeCommit(
      ShellCommandExecutor command, String tree, String parent, String message) throws Exception {
    String person = "Test <test@example.com> " + System.currentTimeMillis() / 1000 + " +0000";
    return writeObject(
        command,
        String.format(
            "tree %s\nparent %s\nauthor %s\ncommitter %s\n\n%s\n",
            tree, parent, person, person, message));
  }
}
