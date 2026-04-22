package com.datadog.profiling.otel.benchmark;

import static com.datadog.profiling.otel.JfrTools.*;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
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
 * Benchmarks for stack trace conversion with varying depths and deduplication ratios.
 *
 * <p>Uses a fixed small event count so that stack trace processing dominates over JFR I/O.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Throughput)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 3, time = 5)
public class StackTraceConversionBenchmark {

  @Param({"5", "15", "30", "50"})
  int stackDepth;

  @Param({"1", "10", "100"})
  int uniqueStacks;

  private static final int EVENTS_PER_STACK = 5;

  private Path jfrFile;
  private JfrToOtlpConverter converter;
  private Instant start;
  private Instant end;

  @Setup(Level.Trial)
  public void setup() throws IOException {
    jfrFile = Files.createTempFile("jfr-stacktrace-bench-", ".jfr");
    converter = new JfrToOtlpConverter();
    Random rnd = new Random(42);

    try (Recording recording = Recordings.newRecording(jfrFile)) {
      Types types = recording.getTypes();
      Type executionSampleType =
          recording.registerEventType(
              "datadog.ExecutionSample",
              type -> {
                type.addField("spanId", Types.Builtin.LONG);
                type.addField("localRootSpanId", Types.Builtin.LONG);
              });

      StackTraceElement[][] stacks = new StackTraceElement[uniqueStacks][];
      for (int s = 0; s < uniqueStacks; s++) {
        stacks[s] = new StackTraceElement[stackDepth];
        for (int f = 0; f < stackDepth; f++) {
          stacks[s][f] =
              new StackTraceElement(
                  "com.example.Class" + (s * 31 + f) % 200,
                  "method" + (s * 17 + f) % 50,
                  "Class.java",
                  10 + f);
        }
      }

      for (int s = 0; s < uniqueStacks; s++) {
        final StackTraceElement[] stack = stacks[s];
        final long spanId = 1000L + s;
        for (int e = 0; e < EVENTS_PER_STACK; e++) {
          writeEvent(
              recording,
              executionSampleType,
              vb -> {
                vb.putField("spanId", spanId);
                vb.putField("localRootSpanId", spanId);
                vb.putField("stackTrace", stb -> putStackTrace(types, stb, stack));
              });
        }
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
  public void convertStackTraces(Blackhole bh) throws IOException {
    byte[] result = converter.addFile(jfrFile, start, end).convert();
    bh.consume(result);
    converter.reset();
  }
}
