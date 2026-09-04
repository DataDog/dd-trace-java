package com.datadog.profiling.otel.benchmark;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.openjdk.jmh.annotations.Mode.Throughput;

import com.datadog.profiling.otel.JfrToOtlpConverter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/** Benchmarks JFR-to-OTLP conversion on real production JFR recordings. */
@State(Scope.Benchmark)
@BenchmarkMode(Throughput)
@OutputTimeUnit(SECONDS)
@Fork(value = 1)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
public class RealFileConversionBenchmark {

  @Param({"/tmp/inventory-cache.jfr", "/tmp/sbom.jfr", "/tmp/otelp.jfr"})
  String jfrFilePath;

  private Path jfrFile;
  private JfrToOtlpConverter converter;
  private Instant start;
  private Instant end;

  @Setup(Level.Trial)
  public void setup() throws IOException {
    jfrFile = Paths.get(jfrFilePath);
    converter = new JfrToOtlpConverter();
    start = Instant.EPOCH;
    end = Instant.now();
  }

  @Benchmark
  public void convertJfrToOtlp(Blackhole bh) throws IOException {
    byte[] result = converter.addFile(jfrFile, start, end).convert();
    bh.consume(result);
    converter.reset();
  }
}
