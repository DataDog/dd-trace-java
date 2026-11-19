package datadog.trace.logging.simplelogger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.logging.LogReporter;
import datadog.trace.logging.PrintStreamWrapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tabletest.junit.TableTest;

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

  @TableTest({
    "Scenario         | Text",
    "ASCII            | a   ",
    "two-byte UTF-8   | é   ",
    "three-byte UTF-8 | 東  ",
    "four-byte UTF-8  | 🙂  "
  })
  void limitsCaptureByUtf8Bytes(String text) throws Exception {
    Path file = directory.resolve("captured.log");
    String message = String.join("", Collections.nCopies(1024, text));
    int lineSize = (message + System.lineSeparator()).getBytes(UTF_8).length;
    int capturedLines = (LogReporter.MAX_LOGFILE_SIZE_BYTES - 1) / lineSize;
    try (PrintStreamWrapper stream =
        new PrintStreamWrapper(new PrintStream(new ByteArrayOutputStream(), true, UTF_8.name()))) {
      stream.startCapturing(file);
      try {
        for (int i = 0; i < capturedLines + 2; i++) {
          stream.println(message);
        }
      } finally {
        stream.stopCapturing();
      }
    }

    assertEquals((long) capturedLines * lineSize, Files.size(file));
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
