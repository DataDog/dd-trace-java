package com.datadog.profiling.otel.benchmark;

import static com.datadog.profiling.otel.JfrTools.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.openjdk.jmh.annotations.Mode.Throughput;

import com.datadog.profiling.otel.JfrToOtlpConverter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Random;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.Types;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * End-to-end benchmarks for JFR-to-OTLP profile conversion. Run
 *
 * <p>Tests full conversion pipeline including:
 *
 * <ul>
 *   <li>JFR file parsing
 *   <li>Event processing
 *   <li>Dictionary deduplication
 *   <li>OTLP protobuf encoding
 * </ul>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Throughput)
@OutputTimeUnit(SECONDS)
@Fork(value = 1)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
public class JfrToOtlpConverterBenchmark {

  @Param({"50", "500", "5000"})
  int eventCount;

  @Param({"10", "50", "100"})
  int stackDepth;

  @Param({"100", "1000"})
  int uniqueContexts;

  /**
   * Percentage of events that reuse existing stack traces. 0 = all unique stacks (worst case for
   * cache), 90 = 90% of events reuse stacks from first 10% (best case for cache, realistic for
   * production workloads).
   */
  @Param({"0", "70", "90"})
  int stackDuplicationPercent;

  private Path jfrFile;
  private JfrToOtlpConverter converter;
  private Instant start;
  private Instant end;

  @Setup(Level.Trial)
  public void setup() throws IOException {
    jfrFile = Files.createTempFile("jfr-otlp-benchmark-", ".jfr");
    converter = new JfrToOtlpConverter();

    // Create JFR recording with synthetic events
    try (Recording recording = Recordings.newRecording(jfrFile)) {
      Types types = recording.getTypes();

      Type executionSampleType =
          recording.registerEventType(
              "datadog.ExecutionSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      Random random = new Random(42);

      // Pre-generate unique stack traces that will be reused
      int uniqueStackCount = Math.max(1, (eventCount * (100 - stackDuplicationPercent)) / 100);
      StackTraceElement[][] uniqueStacks = new StackTraceElement[uniqueStackCount][];

      for (int stackIdx = 0; stackIdx < uniqueStackCount; stackIdx++) {
        StackTraceElement[] stackTrace = new StackTraceElement[stackDepth];
        for (int frameIdx = 0; frameIdx < stackDepth; frameIdx++) {
          int classId = random.nextInt(200);
          int methodId = random.nextInt(50);
          int lineNumber = 10 + random.nextInt(990);

          stackTrace[frameIdx] =
              new StackTraceElement(
                  "com.example.Class" + classId,
                  "method" + methodId,
                  "Class" + classId + ".java",
                  lineNumber);
        }
        uniqueStacks[stackIdx] = stackTrace;
      }

      // Generate events, reusing stacks according to duplication percentage
      for (int i = 0; i < eventCount; i++) {
        // Select stack trace (first uniqueStackCount events get unique stacks, rest reuse)
        int stackIndex = i < uniqueStackCount ? i : random.nextInt(uniqueStackCount);
        final StackTraceElement[] stackTrace = uniqueStacks[stackIndex];

        long contextId = random.nextInt(uniqueContexts);
        final long spanId = 50000L + contextId;
        final long rootSpanId = 60000L + contextId;

        recording.writeEvent(
            executionSampleType.asValue(
                valueBuilder -> {
                  valueBuilder.putField("startTime", System.nanoTime());
                  valueBuilder.putField("spanId", spanId);
                  valueBuilder.putField("localRootSpanId", rootSpanId);
                  valueBuilder.putField(
                      "stackTrace",
                      stackTraceBuilder -> putStackTrace(types, stackTraceBuilder, stackTrace));
                }));
      }
    }

    start = Instant.now().minusSeconds(60);
    end = Instant.now();
  }

  @TearDown(Level.Trial)
  public void tearDown() throws IOException {
    Files.deleteIfExists(jfrFile);
  }

  @Benchmark
  public void convertJfrToOtlp(Blackhole bh) throws IOException {
    byte[] result = converter.addFile(jfrFile, start, end).convert();
    bh.consume(result);
    converter.reset();
  }
}
