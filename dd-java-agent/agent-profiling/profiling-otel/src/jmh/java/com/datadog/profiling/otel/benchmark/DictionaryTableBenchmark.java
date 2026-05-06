package com.datadog.profiling.otel.benchmark;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static org.openjdk.jmh.annotations.Mode.Throughput;

import com.datadog.profiling.otel.proto.dictionary.AttributeTable;
import com.datadog.profiling.otel.proto.dictionary.FunctionTable;
import com.datadog.profiling.otel.proto.dictionary.LinkTable;
import com.datadog.profiling.otel.proto.dictionary.LocationTable;
import com.datadog.profiling.otel.proto.dictionary.StackTable;
import com.datadog.profiling.otel.proto.dictionary.StringTable;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
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

/** Benchmarks for dictionary table deduplication performance. */
@State(Scope.Benchmark)
@BenchmarkMode(Throughput)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 3, time = 5)
public class DictionaryTableBenchmark {

  @Param({"100", "1000", "10000"})
  int uniqueEntries;

  @Param({"0.0", "0.5", "0.95"})
  double hitRate;

  // String table test data
  private StringTable stringTable;
  private String[] testStrings;

  // Function table test data
  private FunctionTable functionTable;
  private int[] functionNameIndices;
  private int[] functionSystemNameIndices;
  private int[] functionFilenameIndices;
  private long[] functionStartLines;

  // Location table test data
  private LocationTable locationTable;
  private int[] locationMappingIndices;
  private long[] locationAddresses;
  private int[] locationFunctionIndices;
  private long[] locationLines;
  private long[] locationColumns;

  // Stack table test data
  private StackTable stackTable;
  private int[][] stackLocationIndices;

  // Link table test data
  private LinkTable linkTable;
  private byte[][] linkTraceIds;
  private byte[][] linkSpanIds;

  // Attribute table test data
  private AttributeTable attributeTable;
  private int[] attributeKeyIndices;
  private long[] attributeValues;
  private int[] attributeUnitIndices;

  @Setup(Level.Trial)
  public void setup() {
    Random rnd = new Random(42);

    // Calculate pool size based on hit rate
    // Lower hit rate = larger pool of unique values
    int poolSize = hitRate == 0.0 ? uniqueEntries * 1000 : (int) (uniqueEntries / (1.0 - hitRate));

    // Setup StringTable
    stringTable = new StringTable();
    testStrings = new String[poolSize];
    for (int i = 0; i < poolSize; i++) {
      testStrings[i] = generateClassName(rnd) + "." + generateMethodName(rnd);
    }

    // Setup FunctionTable
    functionTable = new FunctionTable();
    functionNameIndices = new int[poolSize];
    functionSystemNameIndices = new int[poolSize];
    functionFilenameIndices = new int[poolSize];
    functionStartLines = new long[poolSize];
    for (int i = 0; i < poolSize; i++) {
      functionNameIndices[i] = i;
      functionSystemNameIndices[i] = i;
      functionFilenameIndices[i] = i % 100; // Reuse filenames
      functionStartLines[i] = rnd.nextInt(1000);
    }

    // Setup LocationTable
    locationTable = new LocationTable();
    locationMappingIndices = new int[poolSize];
    locationAddresses = new long[poolSize];
    locationFunctionIndices = new int[poolSize];
    locationLines = new long[poolSize];
    locationColumns = new long[poolSize];
    for (int i = 0; i < poolSize; i++) {
      locationMappingIndices[i] = 0;
      locationAddresses[i] = rnd.nextLong();
      locationFunctionIndices[i] = i;
      locationLines[i] = rnd.nextInt(1000);
      locationColumns[i] = rnd.nextInt(100);
    }

    // Setup StackTable
    stackTable = new StackTable();
    stackLocationIndices = new int[poolSize][];
    for (int i = 0; i < poolSize; i++) {
      int depth = 5 + rnd.nextInt(20); // 5-25 frames
      stackLocationIndices[i] = new int[depth];
      for (int j = 0; j < depth; j++) {
        stackLocationIndices[i][j] = rnd.nextInt(poolSize);
      }
    }

    // Setup LinkTable
    linkTable = new LinkTable();
    linkTraceIds = new byte[poolSize][];
    linkSpanIds = new byte[poolSize][];
    for (int i = 0; i < poolSize; i++) {
      linkTraceIds[i] = new byte[16];
      linkSpanIds[i] = new byte[8];
      rnd.nextBytes(linkTraceIds[i]);
      rnd.nextBytes(linkSpanIds[i]);
    }

    // Setup AttributeTable
    attributeTable = new AttributeTable();
    attributeKeyIndices = new int[poolSize];
    attributeValues = new long[poolSize];
    attributeUnitIndices = new int[poolSize];
    for (int i = 0; i < poolSize; i++) {
      attributeKeyIndices[i] = i % 10; // Reuse keys
      attributeValues[i] = rnd.nextLong();
      attributeUnitIndices[i] = i % 5; // Reuse units
    }
  }

  @Benchmark
  public void internString(Blackhole bh) {
    int idx = ThreadLocalRandom.current().nextInt(testStrings.length);
    int result = stringTable.intern(testStrings[idx]);
    bh.consume(result);
  }

  @Benchmark
  public void internFunction(Blackhole bh) {
    int idx = ThreadLocalRandom.current().nextInt(functionNameIndices.length);
    int result =
        functionTable.intern(
            functionNameIndices[idx],
            functionSystemNameIndices[idx],
            functionFilenameIndices[idx],
            functionStartLines[idx]);
    bh.consume(result);
  }

  @Benchmark
  public void internLocation(Blackhole bh) {
    int idx = ThreadLocalRandom.current().nextInt(locationMappingIndices.length);
    int result =
        locationTable.intern(
            locationMappingIndices[idx],
            locationAddresses[idx],
            locationFunctionIndices[idx],
            locationLines[idx],
            locationColumns[idx]);
    bh.consume(result);
  }

  @Benchmark
  public void internStack(Blackhole bh) {
    int idx = ThreadLocalRandom.current().nextInt(stackLocationIndices.length);
    int result = stackTable.intern(stackLocationIndices[idx]);
    bh.consume(result);
  }

  @Benchmark
  public void internLink(Blackhole bh) {
    int idx = ThreadLocalRandom.current().nextInt(linkTraceIds.length);
    int result = linkTable.intern(linkTraceIds[idx], linkSpanIds[idx]);
    bh.consume(result);
  }

  @Benchmark
  public void internAttribute(Blackhole bh) {
    int idx = ThreadLocalRandom.current().nextInt(attributeKeyIndices.length);
    int result =
        attributeTable.internInt(
            attributeKeyIndices[idx], attributeValues[idx], attributeUnitIndices[idx]);
    bh.consume(result);
  }

  private String generateClassName(Random rnd) {
    String[] packages = {"com.example", "org.apache", "io.netty", "datadog.trace"};
    String[] classes = {"Handler", "Service", "Controller", "Manager", "Factory"};
    return packages[rnd.nextInt(packages.length)]
        + "."
        + classes[rnd.nextInt(classes.length)]
        + rnd.nextInt(100);
  }

  private String generateMethodName(Random rnd) {
    String[] methods = {"process", "handle", "execute", "invoke", "run", "doWork"};
    return methods[rnd.nextInt(methods.length)] + rnd.nextInt(100);
  }
}
