package com.datadog.profiling.otel;

import static com.datadog.profiling.otel.JfrTools.*;
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
      writeEvent(
          recording,
          executionSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", 12345L);
            valueBuilder.putField("localRootSpanId", 67890L);
          });
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
      writeEvent(
          recording,
          methodSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", 11111L);
            valueBuilder.putField("localRootSpanId", 22222L);
          });
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
                type.addField("size", Types.Builtin.LONG);
                type.addField("weight", Types.Builtin.FLOAT);
              });

      // Write object sample event
      writeEvent(
          recording,
          objectSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", 33333L);
            valueBuilder.putField("localRootSpanId", 44444L);
            valueBuilder.putField("size", 1024L);
            valueBuilder.putField("weight", 3.5f);
          });
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
      writeEvent(
          recording,
          monitorEnterType,
          valueBuilder -> {
            valueBuilder.putField("duration", 5000000L); // 5ms in nanos
          });
    }

    Instant start = Instant.now().minusSeconds(10);
    Instant end = Instant.now();

    byte[] result = converter.addFile(jfrFile, start, end).convert();

    assertNotNull(result);
    assertTrue(result.length > 0);
  }

  @Test
  void convertRecordingWithMultipleExecutionSamples() throws IOException {
    Path jfrFile = tempDir.resolve("multiple-cpu.jfr");

    try (Recording recording = Recordings.newRecording(jfrFile)) {
      // Register custom datadog.ExecutionSample event type
      Type executionSampleType =
          recording.registerEventType(
              "datadog.ExecutionSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      // Write multiple execution sample events with different trace contexts
      writeEvent(
          recording,
          executionSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", 100L);
            valueBuilder.putField("localRootSpanId", 200L);
          });

      writeEvent(
          recording,
          executionSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", 300L);
            valueBuilder.putField("localRootSpanId", 400L);
          });

      writeEvent(
          recording,
          executionSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", 100L); // Same as first sample
            valueBuilder.putField("localRootSpanId", 200L);
          });
    }

    Instant start = Instant.now().minusSeconds(10);
    Instant end = Instant.now();

    byte[] result = converter.addFile(jfrFile, start, end).convert();

    assertNotNull(result);
    assertTrue(result.length > 0);
  }

  @Test
  void convertRecordingWithMultipleMethodSamples() throws IOException {
    Path jfrFile = tempDir.resolve("multiple-wall.jfr");

    try (Recording recording = Recordings.newRecording(jfrFile)) {
      // Register custom datadog.MethodSample event type
      Type methodSampleType =
          recording.registerEventType(
              "datadog.MethodSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      // Write multiple method sample events
      for (int i = 0; i < 5; i++) {
        final long spanId = i * 100L;
        final long rootSpanId = i * 100L + 50;
        writeEvent(
            recording,
            methodSampleType,
            valueBuilder -> {
              valueBuilder.putField("spanId", spanId);
              valueBuilder.putField("localRootSpanId", rootSpanId);
            });
      }
    }

    Instant start = Instant.now().minusSeconds(10);
    Instant end = Instant.now();

    byte[] result = converter.addFile(jfrFile, start, end).convert();

    assertNotNull(result);
    assertTrue(result.length > 0);
  }

  @Test
  void convertRecordingWithMultipleObjectSamples() throws IOException {
    Path jfrFile = tempDir.resolve("multiple-alloc.jfr");

    try (Recording recording = Recordings.newRecording(jfrFile)) {
      // Register custom datadog.ObjectSample event type
      Type objectSampleType =
          recording.registerEventType(
              "datadog.ObjectSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
                type.addField("size", Types.Builtin.LONG);
                type.addField("weight", Types.Builtin.FLOAT);
              });

      // Write multiple object sample events with varying allocation sizes
      writeEvent(
          recording,
          objectSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", 1000L);
            valueBuilder.putField("localRootSpanId", 2000L);
            valueBuilder.putField("size", 1024L);
            valueBuilder.putField("weight", 3.5f);
          });

      writeEvent(
          recording,
          objectSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", 3000L);
            valueBuilder.putField("localRootSpanId", 4000L);
            valueBuilder.putField("size", 2048L);
            valueBuilder.putField("weight", 1.5f);
          });

      writeEvent(
          recording,
          objectSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", 1000L); // Same trace as first
            valueBuilder.putField("localRootSpanId", 2000L);
            valueBuilder.putField("size", 4096L);
            valueBuilder.putField("weight", 0.8f);
          });
    }

    Instant start = Instant.now().minusSeconds(10);
    Instant end = Instant.now();

    byte[] result = converter.addFile(jfrFile, start, end).convert();

    assertNotNull(result);
    assertTrue(result.length > 0);
  }

  @Test
  void convertRecordingWithMultipleMonitorSamples() throws IOException {
    Path jfrFile = tempDir.resolve("multiple-lock.jfr");

    try (Recording recording = Recordings.newRecording(jfrFile)) {
      // Register jdk.JavaMonitorEnter event type
      Type monitorEnterType =
          recording.registerEventType(
              "jdk.JavaMonitorEnter",
              type -> {
                type.addField("duration", Types.Builtin.LONG);
              });

      // Write multiple monitor enter events with varying durations
      writeEvent(
          recording,
          monitorEnterType,
          valueBuilder -> {
            valueBuilder.putField("duration", 1000000L); // 1ms
          });

      writeEvent(
          recording,
          monitorEnterType,
          valueBuilder -> {
            valueBuilder.putField("duration", 5000000L); // 5ms
          });

      writeEvent(
          recording,
          monitorEnterType,
          valueBuilder -> {
            valueBuilder.putField("duration", 10000000L); // 10ms
          });
    }

    Instant start = Instant.now().minusSeconds(10);
    Instant end = Instant.now();

    byte[] result = converter.addFile(jfrFile, start, end).convert();

    assertNotNull(result);
    assertTrue(result.length > 0);
  }

  @Test
  void convertRecordingWithMixedEventTypes() throws IOException {
    Path jfrFile = tempDir.resolve("mixed-events.jfr");

    try (Recording recording = Recordings.newRecording(jfrFile)) {
      // Register multiple event types
      Type executionSampleType =
          recording.registerEventType(
              "datadog.ExecutionSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      Type methodSampleType =
          recording.registerEventType(
              "datadog.MethodSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      Type objectSampleType =
          recording.registerEventType(
              "datadog.ObjectSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
                type.addField("size", Types.Builtin.LONG);
                type.addField("weight", Types.Builtin.FLOAT);
              });

      // Write events of different types with same trace context
      long sharedSpanId = 9999L;
      long sharedRootSpanId = 8888L;

      writeEvent(
          recording,
          executionSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", sharedSpanId);
            valueBuilder.putField("localRootSpanId", sharedRootSpanId);
          });

      writeEvent(
          recording,
          methodSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", sharedSpanId);
            valueBuilder.putField("localRootSpanId", sharedRootSpanId);
          });

      writeEvent(
          recording,
          objectSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", sharedSpanId);
            valueBuilder.putField("localRootSpanId", sharedRootSpanId);
            valueBuilder.putField("size", 4096L);
            valueBuilder.putField("weight", 1.1f);
          });

      // Add more ExecutionSamples
      writeEvent(
          recording,
          executionSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", sharedSpanId);
            valueBuilder.putField("localRootSpanId", sharedRootSpanId);
          });
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

      writeEvent(
          recording,
          executionSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", 1L);
            valueBuilder.putField("localRootSpanId", 2L);
          });
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

      writeEvent(
          recording,
          methodSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", 3L);
            valueBuilder.putField("localRootSpanId", 4L);
          });
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

      writeEvent(
          recording,
          executionSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", 42L);
            valueBuilder.putField("localRootSpanId", 42L);
          });
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
  void convertRecordingWithThousandsOfSamples() throws IOException {
    Path jfrFile = tempDir.resolve("thousands-of-samples.jfr");

    // Create recording with 10,000 ExecutionSample events
    // Using 100 unique trace contexts, each repeated 100 times
    try (Recording recording = Recordings.newRecording(jfrFile)) {
      Type executionSampleType =
          recording.registerEventType(
              "datadog.ExecutionSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      // Write 10,000 events with 100 unique trace contexts
      for (int contextId = 0; contextId < 100; contextId++) {
        long spanId = 10000L + contextId;
        long rootSpanId = 20000L + contextId;

        // Each context appears 100 times
        for (int repeat = 0; repeat < 100; repeat++) {
          writeEvent(
              recording,
              executionSampleType,
              valueBuilder -> {
                valueBuilder.putField("spanId", spanId);
                valueBuilder.putField("localRootSpanId", rootSpanId);
              });
        }
      }
    }

    Instant start = Instant.now().minusSeconds(60);
    Instant end = Instant.now();

    byte[] result = converter.addFile(jfrFile, start, end).convert();

    assertNotNull(result, "Result should not be null");
    assertTrue(result.length > 0, "Result should not be empty");
  }

  @Test
  void convertRecordingWithRandomStacktraceDepths() throws IOException {
    Path jfrFile = tempDir.resolve("random-stacks.jfr");

    try (Recording recording = Recordings.newRecording(jfrFile)) {
      // Get Types instance for creating typed values
      Types types = recording.getTypes();

      // Register event type - stackTrace field is added automatically for event types
      Type executionSampleType =
          recording.registerEventType(
              "datadog.ExecutionSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      // Generate 1,000 events with random stack traces of varying depths (5-128 frames)
      // This tests deduplication with diverse but manageable memory footprint
      java.util.Random random = new java.util.Random(42); // Fixed seed for reproducibility
      int eventCount = 5000;

      for (int i = 0; i < eventCount; i++) {
        // Random stack depth between 5 and 128 frames
        int stackDepth = 5 + random.nextInt(124);

        // Generate random stack trace
        StackTraceElement[] stackTrace = new StackTraceElement[stackDepth];
        for (int frameIdx = 0; frameIdx < stackDepth; frameIdx++) {
          // Create diverse class/method names to test deduplication
          int classId = random.nextInt(200); // 200 different classes
          int methodId = random.nextInt(50); // 50 different methods per class
          int lineNumber = 10 + random.nextInt(990); // Random line numbers

          stackTrace[frameIdx] =
              new StackTraceElement(
                  "com.example.Class" + classId,
                  "method" + methodId,
                  "Class" + classId + ".java",
                  lineNumber);
        }

        // Use moderate trace context cardinality (1000 unique contexts)
        long contextId = random.nextInt(1000);
        final long spanId = 50000L + contextId;
        final long rootSpanId = 60000L + contextId;
        final StackTraceElement[] finalStackTrace = stackTrace;

        // Write event with manually constructed stack trace
        recording.writeEvent(
            executionSampleType.asValue(
                valueBuilder -> {
                  valueBuilder.putField("startTime", System.nanoTime());
                  valueBuilder.putField("spanId", spanId);
                  valueBuilder.putField("localRootSpanId", rootSpanId);
                  valueBuilder.putField(
                      "stackTrace",
                      stackTraceBuilder ->
                          putStackTrace(types, stackTraceBuilder, finalStackTrace));
                }));
      }
    }

    Instant start = Instant.now().minusSeconds(60);
    Instant end = Instant.now();

    byte[] result = converter.addFile(jfrFile, start, end).convert();

    assertNotNull(result, "Result should not be null");
    assertTrue(result.length > 0, "Result should not be empty");
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

  @Test
  void convertWithOriginalPayloadDisabledByDefault() throws IOException {
    Path jfrFile = tempDir.resolve("no-payload.jfr");

    try (Recording recording = Recordings.newRecording(jfrFile)) {
      Type executionSampleType =
          recording.registerEventType(
              "datadog.ExecutionSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      writeEvent(
          recording,
          executionSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", 100L);
            valueBuilder.putField("localRootSpanId", 200L);
          });
    }

    Instant start = Instant.now().minusSeconds(10);
    Instant end = Instant.now();

    // Convert without setting includeOriginalPayload (default is false)
    byte[] result = converter.addFile(jfrFile, start, end).convert();

    assertNotNull(result);
    assertTrue(result.length > 0);

    // Result should be smaller than with payload
    // (Note: can't easily verify absence of field in raw protobuf bytes)
  }

  @Test
  void convertWithOriginalPayloadEnabled() throws IOException {
    Path jfrFile = tempDir.resolve("with-payload.jfr");
    long jfrFileSize;

    try (Recording recording = Recordings.newRecording(jfrFile)) {
      Type executionSampleType =
          recording.registerEventType(
              "datadog.ExecutionSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      writeEvent(
          recording,
          executionSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", 100L);
            valueBuilder.putField("localRootSpanId", 200L);
          });
    }

    jfrFileSize = java.nio.file.Files.size(jfrFile);

    Instant start = Instant.now().minusSeconds(10);
    Instant end = Instant.now();

    // Convert WITH original payload
    byte[] resultWithPayload =
        converter.setIncludeOriginalPayload(true).addFile(jfrFile, start, end).convert();

    assertNotNull(resultWithPayload);
    assertTrue(resultWithPayload.length > 0);

    // Result should be at least as large as the JFR file size (contains JFR + OTLP overhead)
    assertTrue(
        resultWithPayload.length >= jfrFileSize,
        String.format(
            "Result size %d should be >= JFR file size %d", resultWithPayload.length, jfrFileSize));
  }

  @Test
  void convertMultipleRecordingsWithOriginalPayload() throws IOException {
    Path jfrFile1 = tempDir.resolve("payload1.jfr");
    Path jfrFile2 = tempDir.resolve("payload2.jfr");
    long totalJfrSize;

    // Create first recording
    try (Recording recording = Recordings.newRecording(jfrFile1)) {
      Type executionSampleType =
          recording.registerEventType(
              "datadog.ExecutionSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      writeEvent(
          recording,
          executionSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", 1L);
            valueBuilder.putField("localRootSpanId", 2L);
          });
    }

    // Create second recording
    try (Recording recording = Recordings.newRecording(jfrFile2)) {
      Type methodSampleType =
          recording.registerEventType(
              "datadog.MethodSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      writeEvent(
          recording,
          methodSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", 3L);
            valueBuilder.putField("localRootSpanId", 4L);
          });
    }

    totalJfrSize = java.nio.file.Files.size(jfrFile1) + java.nio.file.Files.size(jfrFile2);

    Instant start = Instant.now().minusSeconds(20);
    Instant middle = Instant.now().minusSeconds(10);
    Instant end = Instant.now();

    // Convert both recordings with original payload (creates "uber-JFR")
    byte[] result =
        converter
            .setIncludeOriginalPayload(true)
            .addFile(jfrFile1, start, middle)
            .addFile(jfrFile2, middle, end)
            .convert();

    assertNotNull(result);
    assertTrue(result.length > 0);

    // Result should contain concatenated JFR files
    assertTrue(
        result.length >= totalJfrSize,
        String.format(
            "Result size %d should be >= combined JFR size %d", result.length, totalJfrSize));
  }

  @Test
  void converterResetsOriginalPayloadSetting() throws IOException {
    Path jfrFile = tempDir.resolve("reset-test.jfr");

    try (Recording recording = Recordings.newRecording(jfrFile)) {
      Type executionSampleType =
          recording.registerEventType(
              "datadog.ExecutionSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      writeEvent(
          recording,
          executionSampleType,
          valueBuilder -> {
            valueBuilder.putField("spanId", 42L);
            valueBuilder.putField("localRootSpanId", 42L);
          });
    }

    long jfrFileSize = java.nio.file.Files.size(jfrFile);
    Instant start = Instant.now().minusSeconds(10);
    Instant end = Instant.now();

    // First conversion WITH payload
    byte[] result1 =
        converter.setIncludeOriginalPayload(true).addFile(jfrFile, start, end).convert();

    assertTrue(result1.length >= jfrFileSize, "First conversion should include payload");

    // Setting is preserved for reuse (not reset after convert())
    byte[] result2 = converter.addFile(jfrFile, start, end).convert();

    assertTrue(result2.length >= jfrFileSize, "Second conversion should still include payload");

    // Explicitly disable for third conversion
    byte[] result3 =
        converter.setIncludeOriginalPayload(false).addFile(jfrFile, start, end).convert();

    // Third result should be smaller (no payload)
    assertTrue(
        result3.length < result1.length, "Third conversion without payload should be smaller");
  }
}
