package com.datadog.profiling.utils.zstd;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
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

/**
 * Benchmark comparing aircompressor Zstd vs custom Zstd using real JFR files from the jafar
 * project.
 *
 * <p>Tests realistic profiling data compression scenarios with actual JFR recordings.
 *
 * <p>Run with: ./gradlew :dd-java-agent:agent-profiling:profiling-utils:jmh
 * -Pjmh.includes=RealJfrCompressionBenchmark
 */
@BenchmarkMode(AverageTime)
@OutputTimeUnit(MILLISECONDS)
@Warmup(iterations = 2, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xmx4g", "-Xms4g"})
@State(Scope.Benchmark)
public class RealJfrCompressionBenchmark {

  private static final String JAFAR_BASE =
      System.getProperty("user.home") + "/opensource/jafar/demo/src/test/resources";

  @Param({"small", "production", "medium", "medium-full", "large", "xlarge"})
  private String dataSize;

  @Param({"1", "2", "3", "4", "5"})
  private int compressionLevel;

  private byte[] jfrData;
  private String fileName;

  // Track compression metrics
  private long aircompressorCompressedSize = 0;
  private long customCompressedSize = 0;
  private long customLevelCompressedSize = 0;
  private int measurementCount = 0;

  @Setup(Level.Trial)
  public void setup() throws IOException {
    Path jfrPath;
    int maxBytes = Integer.MAX_VALUE;

    switch (dataSize) {
      case "small":
        // 2.3MB file - full compression
        jfrPath = Paths.get(JAFAR_BASE, "test-dd.jfr");
        fileName = "test-dd.jfr (2.3MB)";
        break;
      case "production":
        // 171MB file - read first 30MB chunk (production max)
        jfrPath = Paths.get(JAFAR_BASE, "test-ap.jfr");
        maxBytes = 30 * 1024 * 1024;
        fileName = "test-ap.jfr (30MB production)";
        break;
      case "medium":
        // 171MB file - read first 50MB chunk
        jfrPath = Paths.get(JAFAR_BASE, "test-ap.jfr");
        maxBytes = 50 * 1024 * 1024;
        fileName = "test-ap.jfr (50MB chunk)";
        break;
      case "medium-full":
        // 171MB file - full compression
        jfrPath = Paths.get(JAFAR_BASE, "test-ap.jfr");
        fileName = "test-ap.jfr (171MB full)";
        break;
      case "large":
        // 1.7GB file - read first 100MB chunk
        jfrPath = Paths.get(JAFAR_BASE, "test-jfr.jfr");
        maxBytes = 100 * 1024 * 1024;
        fileName = "test-jfr.jfr (100MB chunk)";
        break;
      case "xlarge":
        // 1.7GB file - read first 400MB chunk
        jfrPath = Paths.get(JAFAR_BASE, "test-jfr.jfr");
        maxBytes = 400 * 1024 * 1024;
        fileName = "test-jfr.jfr (400MB chunk)";
        break;
      default:
        throw new IllegalArgumentException("Unknown dataSize: " + dataSize);
    }

    if (!Files.exists(jfrPath)) {
      throw new IllegalStateException(
          "JFR file not found: "
              + jfrPath
              + ". Please ensure jafar project is cloned to ~/opensource/jafar");
    }

    // Read JFR data
    byte[] fullData = Files.readAllBytes(jfrPath);
    if (fullData.length > maxBytes) {
      jfrData = new byte[maxBytes];
      System.arraycopy(fullData, 0, jfrData, 0, maxBytes);
    } else {
      jfrData = fullData;
    }

    System.out.println(
        "Loaded " + fileName + ": " + String.format("%,d", jfrData.length) + " bytes");
  }

  @Benchmark
  public void aircompressorZstd(Blackhole blackhole) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (io.airlift.compress.zstd.ZstdOutputStream zos =
        new io.airlift.compress.zstd.ZstdOutputStream(baos)) {
      zos.write(jfrData);
    }
    byte[] compressed = baos.toByteArray();
    aircompressorCompressedSize = compressed.length;
    blackhole.consume(compressed.length);
    blackhole.consume(compressed);
  }

  @Benchmark
  public void customZstd(Blackhole blackhole) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    // Pass estimated size for Phase 4 adaptive block sizing optimization
    try (ZstdOutputStream zos = new ZstdOutputStream(baos, jfrData.length)) {
      zos.write(jfrData);
    }
    byte[] compressed = baos.toByteArray();
    customCompressedSize = compressed.length;
    blackhole.consume(compressed.length);
    blackhole.consume(compressed);
  }

  @Benchmark
  public void customZstdWithLevel(Blackhole blackhole) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    // Test different compression levels: 1-2=FAST, 3-4=DFAST, 5=GREEDY
    try (ZstdOutputStream zos = new ZstdOutputStream(baos, jfrData.length, compressionLevel)) {
      zos.write(jfrData);
    }
    byte[] compressed = baos.toByteArray();
    customLevelCompressedSize = compressed.length;
    blackhole.consume(compressed.length);
    blackhole.consume(compressed);
  }

  @Setup(Level.Iteration)
  public void resetCounters() {
    measurementCount++;
  }

  @org.openjdk.jmh.annotations.TearDown(Level.Trial)
  public void printCompressionStats() {
    double airRatio = 100.0 * aircompressorCompressedSize / jfrData.length;
    double customRatio = 100.0 * customCompressedSize / jfrData.length;
    double customLevelRatio = 100.0 * customLevelCompressedSize / jfrData.length;
    double sizeDiff = 100.0 * (customCompressedSize - aircompressorCompressedSize) / aircompressorCompressedSize;
    double levelSizeDiff = 100.0 * (customLevelCompressedSize - aircompressorCompressedSize) / aircompressorCompressedSize;

    System.out.println("\n========================================");
    System.out.println("Compression Ratio Analysis: " + fileName);
    System.out.println("========================================");
    System.out.println(String.format("Original size:         %,d bytes", jfrData.length));
    System.out.println(String.format("Aircompressor size:    %,d bytes (%.2f%% of original)",
        aircompressorCompressedSize, airRatio));
    System.out.println(String.format("Custom size:           %,d bytes (%.2f%% of original)",
        customCompressedSize, customRatio));
    System.out.println(String.format("Custom Level %d size:   %,d bytes (%.2f%% of original)",
        compressionLevel, customLevelCompressedSize, customLevelRatio));
    System.out.println(String.format("Size difference:       %+,d bytes (%+.2f%%)",
        customCompressedSize - aircompressorCompressedSize, sizeDiff));
    System.out.println(String.format("Level %d difference:    %+,d bytes (%+.2f%%)",
        compressionLevel, customLevelCompressedSize - aircompressorCompressedSize, levelSizeDiff));
    System.out.println("========================================\n");
  }
}
