package com.datadog.profiling.otel;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/** Smoke tests for JfrToOtlpConverter using JMC JFR writer to generate test recordings. */
class JfrToOtlpConverterSmokeTest {

  @TempDir Path tempDir;

  private JfrToOtlpConverter converter;

  @BeforeEach
  void setUp() {
    converter = new JfrToOtlpConverter();
  }

  @Test
  void convertEmptyRecording() throws IOException {
    Path jfrFile = tempDir.resolve("empty.jfr");

    // Create empty JFR file with minimal setup
    try (Recording recording = Recordings.newRecording(jfrFile)) {
      // Just create an empty recording - no events
    }

    Instant start = Instant.now().minusSeconds(10);
    Instant end = Instant.now();

    byte[] result = converter.addFile(jfrFile, start, end).convert();

    // Empty recordings produce a valid but minimal protobuf structure
    assertNotNull(result);
  }

  @Test
  void convertRecordingWithExecutionSample() throws IOException {
    Path jfrFile = tempDir.resolve("cpu.jfr");

    try (Recording recording = Recordings.newRecording(jfrFile)) {
      // Register custom datadog.ExecutionSample event type with minimal fields
      Type executionSampleType =
          recording.registerEventType(
              "datadog.ExecutionSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      // Write execution sample event without stack trace for simplicity
      recording.writeEvent(
          executionSampleType.asValue(
              valueBuilder -> {
                valueBuilder.putField("spanId", 12345L);
                valueBuilder.putField("localRootSpanId", 67890L);
              }));
    }

    Instant start = Instant.now().minusSeconds(10);
    Instant end = Instant.now();

    byte[] result = converter.addFile(jfrFile, start, end).convert();

    assertNotNull(result);
    assertTrue(result.length > 0);
  }

  @Test
  void convertRecordingWithMethodSample() throws IOException {
    Path jfrFile = tempDir.resolve("wall.jfr");

    try (Recording recording = Recordings.newRecording(jfrFile)) {
      // Register custom datadog.MethodSample event type
      Type methodSampleType =
          recording.registerEventType(
              "datadog.MethodSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      // Write method sample event
      recording.writeEvent(
          methodSampleType.asValue(
              valueBuilder -> {
                valueBuilder.putField("spanId", 11111L);
                valueBuilder.putField("localRootSpanId", 22222L);
              }));
    }

    Instant start = Instant.now().minusSeconds(10);
    Instant end = Instant.now();

    byte[] result = converter.addFile(jfrFile, start, end).convert();

    assertNotNull(result);
    assertTrue(result.length > 0);
  }

  @Test
  void convertRecordingWithObjectSample() throws IOException {
    Path jfrFile = tempDir.resolve("alloc.jfr");

    try (Recording recording = Recordings.newRecording(jfrFile)) {
      // Register custom datadog.ObjectSample event type
      Type objectSampleType =
          recording.registerEventType(
              "datadog.ObjectSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
                type.addField("allocationSize", Types.Builtin.LONG);
              });

      // Write object sample event
      recording.writeEvent(
          objectSampleType.asValue(
              valueBuilder -> {
                valueBuilder.putField("spanId", 33333L);
                valueBuilder.putField("localRootSpanId", 44444L);
                valueBuilder.putField("allocationSize", 1024L);
              }));
    }

    Instant start = Instant.now().minusSeconds(10);
    Instant end = Instant.now();

    byte[] result = converter.addFile(jfrFile, start, end).convert();

    assertNotNull(result);
    assertTrue(result.length > 0);
  }

  @Test
  void convertRecordingWithJavaMonitorEnter() throws IOException {
    Path jfrFile = tempDir.resolve("lock.jfr");

    try (Recording recording = Recordings.newRecording(jfrFile)) {
      // Register jdk.JavaMonitorEnter event type
      Type monitorEnterType =
          recording.registerEventType(
              "jdk.JavaMonitorEnter",
              type -> {
                type.addField("duration", Types.Builtin.LONG);
              });

      // Write monitor enter event
      recording.writeEvent(
          monitorEnterType.asValue(
              valueBuilder -> {
                valueBuilder.putField("duration", 5000000L); // 5ms in nanos
              }));
    }

    Instant start = Instant.now().minusSeconds(10);
    Instant end = Instant.now();

    byte[] result = converter.addFile(jfrFile, start, end).convert();

    assertNotNull(result);
    assertTrue(result.length > 0);
  }

  @Test
  void convertMultipleRecordings() throws IOException {
    Path jfrFile1 = tempDir.resolve("recording1.jfr");
    Path jfrFile2 = tempDir.resolve("recording2.jfr");

    // Create first recording with execution sample
    try (Recording recording = Recordings.newRecording(jfrFile1)) {
      Type executionSampleType =
          recording.registerEventType(
              "datadog.ExecutionSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      recording.writeEvent(
          executionSampleType.asValue(
              valueBuilder -> {
                valueBuilder.putField("spanId", 1L);
                valueBuilder.putField("localRootSpanId", 2L);
              }));
    }

    // Create second recording with method sample
    try (Recording recording = Recordings.newRecording(jfrFile2)) {
      Type methodSampleType =
          recording.registerEventType(
              "datadog.MethodSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      recording.writeEvent(
          methodSampleType.asValue(
              valueBuilder -> {
                valueBuilder.putField("spanId", 3L);
                valueBuilder.putField("localRootSpanId", 4L);
              }));
    }

    Instant start = Instant.now().minusSeconds(20);
    Instant middle = Instant.now().minusSeconds(10);
    Instant end = Instant.now();

    // Convert both recordings together
    byte[] result =
        converter.addFile(jfrFile1, start, middle).addFile(jfrFile2, middle, end).convert();

    assertNotNull(result);
    assertTrue(result.length > 0);
  }

  @Test
  void converterCanBeReused() throws IOException {
    Path jfrFile = tempDir.resolve("reuse.jfr");

    // Create a recording with a matching event type
    try (Recording recording = Recordings.newRecording(jfrFile)) {
      Type executionSampleType =
          recording.registerEventType(
              "datadog.ExecutionSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      recording.writeEvent(
          executionSampleType.asValue(
              valueBuilder -> {
                valueBuilder.putField("spanId", 42L);
                valueBuilder.putField("localRootSpanId", 42L);
              }));
    }

    Instant start = Instant.now().minusSeconds(10);
    Instant end = Instant.now();

    // First conversion
    byte[] result1 = converter.addFile(jfrFile, start, end).convert();
    assertNotNull(result1);
    assertTrue(result1.length > 0);

    // Second conversion (reusing the same converter)
    byte[] result2 = converter.addFile(jfrFile, start, end).convert();
    assertNotNull(result2);
    assertTrue(result2.length > 0);
  }

  @Test
  void convertEmptyRecordingToJson() throws IOException {
    Path jfrFile = tempDir.resolve("empty.jfr");

    // Create empty JFR file
    try (Recording recording = Recordings.newRecording(jfrFile)) {
      // Just create an empty recording - no events
    }

    Instant start = Instant.now().minusSeconds(10);
    Instant end = Instant.now();

    byte[] result = converter.addFile(jfrFile, start, end).convert(JfrToOtlpConverter.Kind.JSON);

    // Verify JSON output is valid
    assertNotNull(result);
    String json = new String(result, StandardCharsets.UTF_8);
    assertTrue(json.contains("\"resource_profiles\""));
    assertTrue(json.contains("\"dictionary\""));
    System.out.println("JSON output:\n" + json);
  }
}
