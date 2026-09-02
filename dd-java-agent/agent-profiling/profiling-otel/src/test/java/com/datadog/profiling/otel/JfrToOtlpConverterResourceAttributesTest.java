package com.datadog.profiling.otel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;

/** Verifies resource attribute emission for both protobuf and JSON conversion output. */
class JfrToOtlpConverterResourceAttributesTest {
  @TempDir Path tempDir;

  private JfrToOtlpConverter converter;
  private Map<String, String> attributes;

  @BeforeEach
  void setUp() {
    converter = new JfrToOtlpConverter();
    attributes = new LinkedHashMap<>();
    attributes.put("service.name", "test-service");
    attributes.put("deployment.environment.name", "test");
    attributes.put("telemetry.sdk.name", "datadog");
  }

  @Test
  void protobufOutputCarriesResourceAttributes() throws IOException {
    Path jfrFile = tempDir.resolve("empty.jfr");
    try (Recording recording = Recordings.newRecording(jfrFile)) {
      // empty recording — resource attributes are independent of sample content
    }

    byte[] result =
        converter
            .setResourceAttributes(attributes)
            .addFile(jfrFile, Instant.now().minusSeconds(10), Instant.now())
            .convert(JfrToOtlpConverter.Kind.PROTO);

    String payload = new String(result, StandardCharsets.ISO_8859_1);
    // String fields are encoded inline on the wire, so key/value pairs are searchable
    assertTrue(payload.contains("service.name"));
    assertTrue(payload.contains("test-service"));
    assertTrue(payload.contains("telemetry.sdk.name"));
  }

  @Test
  void jsonOutputCarriesResourceAttributes() throws IOException {
    Path jfrFile = tempDir.resolve("empty-json.jfr");
    try (Recording recording = Recordings.newRecording(jfrFile)) {
      // empty recording
    }

    byte[] result =
        converter
            .setResourceAttributes(attributes)
            .addFile(jfrFile, Instant.now().minusSeconds(10), Instant.now())
            .convert(JfrToOtlpConverter.Kind.JSON);

    String payload = new String(result, StandardCharsets.UTF_8);
    assertTrue(payload.contains("\"resource\""));
    assertTrue(payload.contains("service.name"));
    assertTrue(payload.contains("test-service"));
  }

  @Test
  void emptyAttributesOmitResourceMessage() throws IOException {
    Path jfrFile = tempDir.resolve("empty-none.jfr");
    try (Recording recording = Recordings.newRecording(jfrFile)) {
      // empty recording
    }

    byte[] result =
        converter
            .addFile(jfrFile, Instant.now().minusSeconds(10), Instant.now())
            .convert(JfrToOtlpConverter.Kind.PROTO);

    String payload = new String(result, StandardCharsets.ISO_8859_1);
    assertFalse(payload.contains("service.name"));
  }
}
