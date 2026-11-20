package datadog.trace.civisibility.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.communication.util.IOUtils;
import datadog.trace.test.util.PortableCommand;
import java.nio.file.Path;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellCommandExecutorTest {
  private static final long COMMAND_TIMEOUT_MILLIS = 5_000;
  @TempDir Path directory;

  @Test
  void executesCommands() throws Exception {
    ShellCommandExecutor executor =
        new ShellCommandExecutor(directory.toFile(), COMMAND_TIMEOUT_MILLIS);
    assertEquals(
        "this is a test",
        executor.executeCommand(IOUtils::readFully, PortableCommand.echo("this is a test")).trim());
  }

  @Test
  void writesCommandInput() throws Exception {
    ShellCommandExecutor executor =
        new ShellCommandExecutor(directory.toFile(), COMMAND_TIMEOUT_MILLIS);
    assertEquals(
        "this is a test",
        executor
            .executeCommand(
                IOUtils::readFully,
                "this is a test".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                PortableCommand.cat())
            .trim());
  }

  @Test
  void enforcesCommandTimeout() {
    ShellCommandExecutor executor = new ShellCommandExecutor(directory.toFile(), 1_000);
    assertThrows(
        TimeoutException.class,
        () -> executor.executeCommand(IOUtils::readFully, PortableCommand.sleep(2)));
  }
}
