package datadog.trace.logging.simplelogger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import datadog.trace.logging.PrintStreamWrapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogEncodingTest {
  private static final String MESSAGE = "café 東京";

  @TempDir Path directory;

  @Test
  void capturesLogsAsUtf8() throws Exception {
    Path file = directory.resolve("captured.log");
    try (PrintStreamWrapper stream =
        new PrintStreamWrapper(new PrintStream(new ByteArrayOutputStream(), true, UTF_8.name()))) {
      stream.startCapturing(file);
      try {
        stream.println(MESSAGE);
      } finally {
        stream.stopCapturing();
      }
    }

    assertArrayEquals((MESSAGE + System.lineSeparator()).getBytes(UTF_8), Files.readAllBytes(file));
  }

  @Test
  void writesConfiguredLogFileAsUtf8() throws Exception {
    Path file = directory.resolve("configured.log");
    try (PrintStream stream = SLCompatSettings.getPrintStream(file.toString())) {
      stream.println(MESSAGE);
    }

    assertArrayEquals((MESSAGE + System.lineSeparator()).getBytes(UTF_8), Files.readAllBytes(file));
  }
}
