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

final class GitTestRepository {
  static ShellCommandExecutor fixture(Path checkout) throws Exception {
    Path fixture = Paths.get(GitTestRepository.class.getResource("/ci/git/with_pack/git").toURI());
    IOUtils.copyFolder(fixture, Files.createDirectories(checkout.resolve(".git")));
    return new ShellCommandExecutor(checkout.toFile(), 10_000);
  }

  static String git(ShellCommandExecutor command, String... args) throws Exception {
    String[] gitCommand = Stream.concat(Stream.of("git"), Stream.of(args)).toArray(String[]::new);
    return command.executeCommand(IOUtils::readFully, gitCommand).trim();
  }

  static ShellGitClient client(Path checkout, String since) {
    return new ShellGitClient(
        mock(CiVisibilityMetricCollector.class), checkout.toString(), since, 1000, 10_000);
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
