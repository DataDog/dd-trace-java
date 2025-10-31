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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class HotspotCrashLogParserTest {
  @ParameterizedTest
  @CsvSource({
    // From sample-crash-for-telemetry - 2 digits time offset
    "Tue Oct 17 20:25:14 2023 +08,      2023-10-17T20:25:14+08:00",
    // From sample-crash-for-telemetry-2 - UTC time zone name
    "Fri Sep 20 13:19:06 2024 UTC,      2024-09-20T13:19:06Z",
    // Test single digit day
    "Tue Oct  1 05:05:47 2024 +00:00,   2024-10-01T05:05:47Z",
    // Test CEST time zone name
    "Tue Oct  1 14:37:58 2024 CEST,     2024-10-01T14:37:58+02:00"
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

  private String readFileAsString(String resource) throws IOException {
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
      return new BufferedReader(
              new InputStreamReader(Objects.requireNonNull(stream), StandardCharsets.UTF_8))
          .lines()
          .collect(Collectors.joining("\n"));
    }
  }
}
