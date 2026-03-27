package datadog.crashtracking.parsers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.crashtracking.dto.CrashLog;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

public class HotspotCrashLogParserTest {
  @TableTest({
    "scenario         | logDateTime                     | expectedISODateTime      ",
    "2-digit offset   | Tue Oct 17 20:25:14 2023 +08    | 2023-10-17T20:25:14+08:00",
    "UTC zone         | Fri Sep 20 13:19:06 2024 UTC    | 2024-09-20T13:19:06Z     ",
    "single-digit day | Tue Oct  1 05:05:47 2024 +00:00 | 2024-10-01T05:05:47Z     ",
    "CEST zone        | Tue Oct  1 14:37:58 2024 CEST   | 2024-10-01T14:37:58+02:00"
  })
  public void testDateTimeParser(String logDateTime, String expectedISODateTime) {
    assertEquals(expectedISODateTime, HotspotCrashLogParser.dateTimeToISO(logDateTime));
  }

  @Test
  public void testIncompleteParsing() throws Exception {
    // Given
    final String uuid = UUID.randomUUID().toString();

    // When
    final CrashLog crashLog =
        new HotspotCrashLogParser().parse(uuid, readFileAsString("incomplete-crash.txt"));

    // Then
    assertNotNull(crashLog);
    assertEquals(uuid, crashLog.uuid);
    assertTrue(crashLog.incomplete);
    assertNotNull(crashLog.error);
    assertNotNull(crashLog.error.stack);
    assertNotNull(crashLog.error.stack.frames);
    assertEquals(0, crashLog.error.stack.frames.length);
  }

  /** macOS aarch64 uses lowercase register names: x0-x28, fp, lr, sp, pc, cpsr */
  @Test
  public void testRegisterParsingMacosAarch64() throws Exception {
    CrashLog crashLog =
        new HotspotCrashLogParser()
            .parse(
                UUID.randomUUID().toString(), readFileAsString("sample-crash-macos-aarch64.txt"));

    assertNotNull(crashLog.experimental, "experimental field should be populated");
    assertNotNull(crashLog.experimental.ucontext, "ucontext should be populated");
    assertEquals("0x0000000000000c55", crashLog.experimental.ucontext.get("x0"));
    assertEquals("0x0000000000000000", crashLog.experimental.ucontext.get("x2"));
    assertEquals("0x000000016feee210", crashLog.experimental.ucontext.get("fp"));
    assertEquals("0x0000000116d0c970", crashLog.experimental.ucontext.get("lr"));
    assertEquals("0x000000016feee0f0", crashLog.experimental.ucontext.get("sp"));
    assertEquals("0x000000010f8ac794", crashLog.experimental.ucontext.get("pc"));
    assertEquals("0x0000000060001000", crashLog.experimental.ucontext.get("cpsr"));
  }

  /** Linux aarch64 uses uppercase register names: R0-R30 */
  @Test
  public void testRegisterParsingLinuxAarch64() throws Exception {
    CrashLog crashLog =
        new HotspotCrashLogParser()
            .parse(
                UUID.randomUUID().toString(), readFileAsString("sample-crash-linux-aarch64.txt"));

    assertNotNull(crashLog.experimental, "experimental field should be populated");
    assertNotNull(crashLog.experimental.ucontext, "ucontext should be populated");
    assertEquals("0x0000000000000000", crashLog.experimental.ucontext.get("R0"));
    assertEquals("0x0000000000000001", crashLog.experimental.ucontext.get("R1"));
    assertEquals("0x0000ffff9efa168c", crashLog.experimental.ucontext.get("R30"));
    // "Register to memory mapping:" section must NOT be included
    assertEquals(31, crashLog.experimental.ucontext.size(), "R0-R30 = 31 registers");
  }

  @TableTest({
    "scenario      | filename                        ",
    "RHEL amd64    | sample-crash-for-telemetry.txt  ",
    "Ubuntu amd64  | sample-crash-for-telemetry-2.txt",
    "macOS aarch64 | sample_oom.txt                  ",
    "macOS JDK 8   | sample-crash-for-telemetry-3.txt"
  })
  public void testOsInfoFromSystemProperties(String filename) throws Exception {
    final CrashLog crashLog =
        new HotspotCrashLogParser().parse(UUID.randomUUID().toString(), readFileAsString(filename));
    assertNotNull(crashLog.osInfo);
  }

  @Test
  public void testFrameTypesFromHotspotStack() throws Exception {
    final CrashLog crashLog =
        new HotspotCrashLogParser()
            .parse(UUID.randomUUID().toString(), readFileAsString("sample-crash-for-telemetry.txt"));

    assertEquals("vm", crashLog.error.stack.frames[0].frameType);
    assertEquals("native", crashLog.error.stack.frames[3].frameType);
    assertEquals("stub", crashLog.error.stack.frames[15].frameType);
    assertEquals("compiled", crashLog.error.stack.frames[16].frameType);
    assertEquals("compiled", crashLog.error.stack.frames[17].frameType);
    assertEquals("interpreted", crashLog.error.stack.frames[66].frameType);
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
