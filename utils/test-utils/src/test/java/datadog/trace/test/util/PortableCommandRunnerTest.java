package datadog.trace.test.util;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the command dispatch. In production the runner only ever executes as a child
 * process, where its behavior is covered end to end by the emulated cases of {@link
 * PortableCommandTest} — but a child JVM is opaque to both the coverage report and to assertions
 * about why a command misbehaved, so the dispatch is driven directly here.
 */
class PortableCommandRunnerTest {
  @Test
  void echoes() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    PortableCommandRunner.execute(new String[] {"echo", "value"}, emptyInput(), output);

    assertEquals("value" + System.lineSeparator(), new String(output.toByteArray(), UTF_8));
  }

  @Test
  void copiesInput() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    InputStream input = new ByteArrayInputStream("payload".getBytes(UTF_8));

    PortableCommandRunner.execute(new String[] {"cat"}, input, output);

    assertEquals("payload", new String(output.toByteArray(), UTF_8));
  }

  @Test
  void sleeps() throws Exception {
    long start = System.nanoTime();

    PortableCommandRunner.execute(
        new String[] {"sleep", "50"}, emptyInput(), new ByteArrayOutputStream());

    assertTrue((System.nanoTime() - start) / 1_000_000 >= 40, "sleep returned immediately");
  }

  @Test
  void rejectsMissingCommand() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PortableCommandRunner.execute(
                new String[0], emptyInput(), new ByteArrayOutputStream()));
  }

  @Test
  void rejectsUnknownCommand() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PortableCommandRunner.execute(
                new String[] {"rm"}, emptyInput(), new ByteArrayOutputStream()));
  }

  @Test
  void rejectsMissingArgument() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PortableCommandRunner.execute(
                new String[] {"echo"}, emptyInput(), new ByteArrayOutputStream()));
  }

  private static InputStream emptyInput() {
    return new ByteArrayInputStream(new byte[0]);
  }
}
