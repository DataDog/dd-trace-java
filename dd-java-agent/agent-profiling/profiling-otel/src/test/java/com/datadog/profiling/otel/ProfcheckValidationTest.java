/*
 * Copyright 2025 Datadog
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datadog.profiling.otel;

import static com.datadog.profiling.otel.JfrTools.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.Types;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * Integration test that validates OTLP profiles against OpenTelemetry's profcheck conformance
 * checker.
 *
 * <p>This test:
 *
 * <ul>
 *   <li>Generates synthetic JFR recordings using JMC API
 *   <li>Converts them to OTLP protobuf format
 *   <li>Validates with profcheck running in a Docker container
 * </ul>
 *
 * <p>Requires Docker to be running.
 */
@Testcontainers
@Tag("docker")
public class ProfcheckValidationTest {

  // Profcheck container built from Dockerfile.profcheck
  // Note: We override the entrypoint to keep the container running since profcheck
  // normally exits after validation. We use the container for multiple validations.
  @Container
  private static final GenericContainer<?> profcheckContainer =
      new GenericContainer<>("profcheck:latest")
          .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh"))
          .withCommand("-c", "while true; do sleep 1; done");

  @TempDir Path tempDir;

  @Test
  public void testEmptyProfile() throws Exception {
    // Generate empty JFR recording
    Path jfrFile = tempDir.resolve("empty.jfr");
    try (Recording recording = Recordings.newRecording(jfrFile)) {
      // Empty recording - no events
    }

    // Convert to OTLP
    Path otlpFile = tempDir.resolve("empty.pb");
    byte[] otlpData = convertJfrToOtlp(jfrFile);
    Files.write(otlpFile, otlpData);

    // Validate with profcheck (now includes protoc validation)
    String result = validateWithProfcheck(otlpFile);

    // Empty profiles should pass protoc structural validation
    assertTrue(
        result.contains("protoc validation PASSED"),
        "Empty profile should pass protoc validation (spec-compliant). Output: " + result);
  }

  @Test
  public void testCpuProfile() throws Exception {
    // Generate JFR recording with CPU samples
    Path jfrFile = tempDir.resolve("cpu.jfr");

    try (Recording recording = Recordings.newRecording(jfrFile)) {
      Types types = recording.getTypes();

      Type executionSampleType =
          recording.registerEventType(
              "datadog.ExecutionSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      // Add 100 CPU samples with various stack traces
      for (int i = 0; i < 100; i++) {
        final long spanId = 10000L + i;
        final long rootSpanId = 20000L + (i % 10);

        StackTraceElement[] stackTrace =
            new StackTraceElement[] {
              new StackTraceElement("com.example.App", "main", "App.java", 42),
              new StackTraceElement("com.example.Service", "process", "Service.java", 123),
              new StackTraceElement("com.example.Util", "helper", "Util.java", 78)
            };

        recording.writeEvent(
            executionSampleType.asValue(
                valueBuilder -> {
                  valueBuilder.putField("spanId", spanId);
                  valueBuilder.putField("localRootSpanId", rootSpanId);
                  valueBuilder.putField(
                      "stackTrace",
                      stackTraceBuilder -> putStackTrace(types, stackTraceBuilder, stackTrace));
                }));
      }
    }

    // Convert to OTLP
    Path otlpFile = tempDir.resolve("cpu.pb");
    byte[] otlpData = convertJfrToOtlp(jfrFile);
    Files.write(otlpFile, otlpData);

    // Validate with profcheck (includes both protoc and profcheck validation)
    String result = validateWithProfcheck(otlpFile);

    // Both validators must pass
    assertTrue(
        result.contains("protoc validation PASSED"),
        "CPU profile should pass protoc validation (spec-compliant). Output: " + result);
    assertTrue(
        result.contains("profcheck validation PASSED"),
        "CPU profile should pass profcheck validation. Output: " + result);
  }

  @Test
  public void testAllocationProfile() throws Exception {
    // Generate JFR recording with allocation samples
    Path jfrFile = tempDir.resolve("alloc.jfr");

    try (Recording recording = Recordings.newRecording(jfrFile)) {
      Types types = recording.getTypes();

      Type objectSampleType =
          recording.registerEventType(
              "datadog.ObjectSample",
              type -> {
                type.addField("size", Types.Builtin.LONG);
                type.addField("weight", Types.Builtin.FLOAT);
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      // Add 50 allocation samples
      for (int i = 0; i < 50; i++) {
        final long weight = 1024L * (i + 1);
        final long spanId = 30000L + i;
        final long rootSpanId = 40000L + (i % 5);

        StackTraceElement[] stackTrace =
            new StackTraceElement[] {
              new StackTraceElement("com.example.Factory", "create", "Factory.java", 55),
              new StackTraceElement("com.example.Builder", "build", "Builder.java", 89)
            };

        recording.writeEvent(
            objectSampleType.asValue(
                valueBuilder -> {
                  valueBuilder.putField("size", weight);
                  valueBuilder.putField("weight", 0.9f);
                  valueBuilder.putField("spanId", spanId);
                  valueBuilder.putField("localRootSpanId", rootSpanId);
                  valueBuilder.putField(
                      "stackTrace",
                      stackTraceBuilder -> putStackTrace(types, stackTraceBuilder, stackTrace));
                }));
      }
    }

    // Convert to OTLP
    Path otlpFile = tempDir.resolve("alloc.pb");
    byte[] otlpData = convertJfrToOtlp(jfrFile);
    Files.write(otlpFile, otlpData);

    // Validate with profcheck (includes both protoc and profcheck validation)
    String result = validateWithProfcheck(otlpFile);

    // Both validators must pass
    assertTrue(
        result.contains("protoc validation PASSED"),
        "Allocation profile should pass protoc validation (spec-compliant). Output: " + result);
    assertTrue(
        result.contains("profcheck validation PASSED"),
        "Allocation profile should pass profcheck validation. Output: " + result);
  }

  @Test
  public void testMixedProfile() throws Exception {
    // Generate JFR recording with multiple event types
    Path jfrFile = tempDir.resolve("mixed.jfr");

    try (Recording recording = Recordings.newRecording(jfrFile)) {
      Types types = recording.getTypes();

      // CPU samples
      Type executionSampleType =
          recording.registerEventType(
              "datadog.ExecutionSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      // Wall clock samples
      Type methodSampleType =
          recording.registerEventType(
              "datadog.MethodSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      StackTraceElement[] stackTrace =
          new StackTraceElement[] {
            new StackTraceElement("com.example.Main", "run", "Main.java", 100)
          };

      // Add mix of events
      for (int i = 0; i < 20; i++) {
        final long spanId = 50000L + i;
        final long rootSpanId = 60000L;

        // CPU sample
        recording.writeEvent(
            executionSampleType.asValue(
                valueBuilder -> {
                  valueBuilder.putField("spanId", spanId);
                  valueBuilder.putField("localRootSpanId", rootSpanId);
                  valueBuilder.putField(
                      "stackTrace",
                      stackTraceBuilder -> putStackTrace(types, stackTraceBuilder, stackTrace));
                }));

        // Wall clock sample
        recording.writeEvent(
            methodSampleType.asValue(
                valueBuilder -> {
                  valueBuilder.putField("spanId", spanId);
                  valueBuilder.putField("localRootSpanId", rootSpanId);
                  valueBuilder.putField(
                      "stackTrace",
                      stackTraceBuilder -> putStackTrace(types, stackTraceBuilder, stackTrace));
                }));
      }
    }

    // Convert to OTLP
    Path otlpFile = tempDir.resolve("mixed.pb");
    byte[] otlpData = convertJfrToOtlp(jfrFile);
    Files.write(otlpFile, otlpData);

    // Validate with profcheck (includes both protoc and profcheck validation)
    String result = validateWithProfcheck(otlpFile);

    // Both validators must pass
    assertTrue(
        result.contains("protoc validation PASSED"),
        "Mixed profile should pass protoc validation (spec-compliant). Output: " + result);
    assertTrue(
        result.contains("profcheck validation PASSED"),
        "Mixed profile should pass profcheck validation. Output: " + result);
  }

  private byte[] convertJfrToOtlp(Path jfrFile) throws IOException {
    Instant start = Instant.now().minusSeconds(60);
    Instant end = Instant.now();

    JfrToOtlpConverter converter = new JfrToOtlpConverter();
    return converter.addFile(jfrFile, start, end).convert();
  }

  private String validateWithProfcheck(Path otlpFile) throws Exception {
    // Copy file into container
    profcheckContainer.copyFileToContainer(
        MountableFile.forHostPath(otlpFile), "/tmp/" + otlpFile.getFileName());

    // Run validate-profile script (includes protoc + profcheck)
    org.testcontainers.containers.Container.ExecResult result =
        profcheckContainer.execInContainer("validate-profile", "/tmp/" + otlpFile.getFileName());

    String output = result.getStdout() + result.getStderr();

    // Log output for debugging
    System.out.println("Validation output for " + otlpFile.getFileName() + ":");
    System.out.println(output);

    return output;
  }
}
