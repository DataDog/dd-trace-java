package datadog.trace.civisibility.utils;

import static datadog.trace.test.util.PortableCommand.cat;
import static datadog.trace.test.util.PortableCommand.echo;
import static datadog.trace.test.util.PortableCommand.sleep;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import datadog.communication.util.IOUtils;
import datadog.trace.civisibility.utils.ShellCommandExecutor.OutputParser;
import datadog.trace.civisibility.utils.ShellCommandExecutor.ShellCommandFailedException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShellCommandExecutorTest {
  private static final long COMMAND_TIMEOUT_MILLIS = 5_000;
  private static final String SECRET = "Authorization: test-secret";
  @TempDir Path directory;

  @Test
  void executesCommands() throws Exception {
    ShellCommandExecutor executor =
        new ShellCommandExecutor(directory.toFile(), COMMAND_TIMEOUT_MILLIS);
    assertEquals(
        "this is a test",
        executor.executeCommand(IOUtils::readFully, echo("this is a test")).trim());
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
                cat())
            .trim());
  }

  @Test
  void enforcesCommandTimeout() {
    ShellCommandExecutor executor = new ShellCommandExecutor(directory.toFile(), 1_000);
    assertThrows(
        TimeoutException.class, () -> executor.executeCommand(IOUtils::readFully, sleep(2)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"timeout", "io", "exit", "interruption"})
  void sensitiveFailuresNeverRetainCommandOutput(String kind) throws Exception {
    ShellCommandExecutor executor =
        spy(new ShellCommandExecutor(directory.toFile(), COMMAND_TIMEOUT_MILLIS));
    Exception failure =
        "timeout".equals(kind)
            ? new TimeoutException(SECRET)
            : "io".equals(kind)
                ? new IOException(SECRET)
                : "exit".equals(kind)
                    ? new ShellCommandFailedException(128, SECRET)
                    : new InterruptedException("cancelled");
    doThrow(failure).when(executor).executeCommand(OutputParser.IGNORE, "git", "config");
    Exception actual =
        assertThrows(
            failure.getClass(),
            () -> executor.executeSensitiveCommand(OutputParser.IGNORE, "git", "config"));
    if (failure instanceof InterruptedException) {
      assertSame(failure, actual);
    } else {
      assertFalse(actual.getMessage().contains(SECRET));
      assertNull(actual.getCause());
      assertEquals(0, actual.getSuppressed().length);
      if (actual instanceof ShellCommandFailedException) {
        assertEquals(128, ((ShellCommandFailedException) actual).getExitCode());
      }
    }
  }
}
