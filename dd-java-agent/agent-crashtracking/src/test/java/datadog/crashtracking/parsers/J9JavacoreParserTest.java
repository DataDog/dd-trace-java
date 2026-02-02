package datadog.crashtracking.parsers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.crashtracking.dto.CrashLog;
import datadog.crashtracking.dto.StackFrame;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public class J9JavacoreParserTest {

  @Test
  public void testParseGpfCrash() throws Exception {
    // Given
    final String uuid = UUID.randomUUID().toString();
    String javacoreContent = readFileAsString("sample-j9-javacore-gpf.txt");

    // When
    final CrashLog crashLog = new J9JavacoreParser().parse(uuid, javacoreContent);

    // Then
    assertNotNull(crashLog);
    assertEquals(uuid, crashLog.uuid);
    assertFalse(crashLog.incomplete);
    assertEquals("1.0", crashLog.dataSchemaVersion);

    // Signal info
    assertNotNull(crashLog.sigInfo);
    assertEquals("SIGSEGV", crashLog.sigInfo.name);
    assertEquals(11, crashLog.sigInfo.number);

    // Process info
    assertNotNull(crashLog.procInfo);
    assertEquals("12345", crashLog.procInfo.pid);

    // Error info
    assertNotNull(crashLog.error);
    assertEquals("SIGSEGV", crashLog.error.kind);
    assertEquals("Process terminated by signal SIGSEGV", crashLog.error.message);

    // Stack trace
    assertNotNull(crashLog.error.stack);
    assertNotNull(crashLog.error.stack.frames);
    assertTrue(crashLog.error.stack.frames.length > 0);

    // Check first Java frame
    assertEquals("com/example/NativeLibrary.crash", crashLog.error.stack.frames[0].function);

    // Check second Java frame with source info
    assertEquals("com/example/CrashingApp.triggerCrash", crashLog.error.stack.frames[1].function);
    assertEquals("CrashingApp.java", crashLog.error.stack.frames[1].file);
    assertEquals(Integer.valueOf(42), crashLog.error.stack.frames[1].line);

    // Check native frames are present
    assertTrue(crashLog.error.stack.frames.length >= 4); // 3 java + native frames
  }

  @Test
  public void testParseOomCrash() throws Exception {
    // Given
    final String uuid = UUID.randomUUID().toString();
    String javacoreContent = readFileAsString("sample-j9-javacore-oom.txt");

    // When
    final CrashLog crashLog = new J9JavacoreParser().parse(uuid, javacoreContent);

    // Then
    assertNotNull(crashLog);
    assertEquals(uuid, crashLog.uuid);
    assertFalse(crashLog.incomplete);

    // Error should be OutOfMemory type
    assertNotNull(crashLog.error);
    assertEquals("OutOfMemory", crashLog.error.kind);
    assertTrue(crashLog.error.message.contains("OutOfMemory"));

    // Process info
    assertNotNull(crashLog.procInfo);
    assertEquals("54321", crashLog.procInfo.pid);

    // Stack trace should show the allocation path
    assertNotNull(crashLog.error.stack);
    assertNotNull(crashLog.error.stack.frames);
    assertTrue(crashLog.error.stack.frames.length > 0);

    // Check that ArrayList.grow is in the stack (where OOM typically occurs)
    boolean foundGrow = false;
    for (StackFrame frame : crashLog.error.stack.frames) {
      if (frame.function != null && frame.function.contains("ArrayList.grow")) {
        foundGrow = true;
        break;
      }
    }
    assertTrue(foundGrow, "Expected ArrayList.grow in stack trace");
  }

  @Test
  public void testParseIncompleteJavacore() throws Exception {
    // Given
    final String uuid = UUID.randomUUID().toString();
    // An incomplete javacore that's missing the THREADS section
    String incompleteJavacore =
        "0SECTION       TITLE subcomponent dump routine\n"
            + "NULL           ===============================\n"
            + "1TICHARSET     UTF-8\n"
            + "1TISIGINFO     Dump Event \"gpf\" (00002000) received\n"
            + "1TIDATETIME    Date: 2024/08/25 at 15:55:09:123\n";

    // When
    final CrashLog crashLog = new J9JavacoreParser().parse(uuid, incompleteJavacore);

    // Then
    assertNotNull(crashLog);
    assertTrue(crashLog.incomplete);
    assertEquals(0, crashLog.error.stack.frames.length);
  }

  @Test
  public void testAbortCrash() throws Exception {
    // Given - a javacore for an abort event
    final String uuid = UUID.randomUUID().toString();
    String javacoreContent =
        "0SECTION       TITLE subcomponent dump routine\n"
            + "NULL           ===============================\n"
            + "1TICHARSET     UTF-8\n"
            + "1TISIGINFO     Dump Event \"abort\" (00000020) received\n"
            + "1TIDATETIME    Date: 2024/10/01 at 08:30:00:000\n"
            + "NULL           ------------------------------------------------------------------------\n"
            + "0SECTION       ENVINFO subcomponent dump routine\n"
            + "NULL           =================================\n"
            + "1CIPROCESSID   Process ID: 99999\n"
            + "NULL           ------------------------------------------------------------------------\n"
            + "0SECTION       THREADS subcomponent dump routine\n"
            + "NULL           =================================\n"
            + "1XMCURTHDINFO  Current thread: \"abort-thread\" (J9VMThread:0x00000001)\n"
            + "NULL\n"
            + "3XMTHREADINFO      \"abort-thread\" J9VMThread:0x00000001, state:R, prio=5\n"
            + "3XMTHREADINFO3           Java callstack:\n"
            + "4XESTACKTRACE                at java/lang/Runtime.exit(Runtime.java:123)\n"
            + "NULL\n";

    // When
    final CrashLog crashLog = new J9JavacoreParser().parse(uuid, javacoreContent);

    // Then
    assertNotNull(crashLog);
    assertNotNull(crashLog.sigInfo);
    assertEquals("SIGABRT", crashLog.sigInfo.name);
    assertEquals(6, crashLog.sigInfo.number);
    assertEquals("99999", crashLog.procInfo.pid);
  }

  @Test
  public void testDateTimeParsing() throws Exception {
    // Given
    final String uuid = UUID.randomUUID().toString();
    String javacoreContent = readFileAsString("sample-j9-javacore-gpf.txt");

    // When
    final CrashLog crashLog = new J9JavacoreParser().parse(uuid, javacoreContent);

    // Then - timestamp should be in ISO format
    assertNotNull(crashLog.timestamp);
    // Should be ISO-8601 format with offset
    assertTrue(
        crashLog.timestamp.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*"),
        "Expected ISO-8601 format, got: " + crashLog.timestamp);
  }

  private String readFileAsString(String resource) throws IOException {
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
      return new BufferedReader(
              new InputStreamReader(Objects.requireNonNull(stream), StandardCharsets.UTF_8))
          .lines()
          .collect(Collectors.joining("\n"));
    }
  }
}
