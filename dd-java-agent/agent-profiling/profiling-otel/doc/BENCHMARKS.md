# OTLP Profiling Benchmarks

This module includes JMH microbenchmarks to measure the performance of critical hot-path operations.

## Quick Start

Run all benchmarks (comprehensive):

```bash
./gradlew :dd-java-agent:agent-profiling:profiling-otel:jmh
```

Run specific benchmarks for faster feedback:

```bash
# Run only end-to-end converter benchmark
./gradlew :dd-java-agent:agent-profiling:profiling-otel:jmh -PjmhIncludes="JfrToOtlpConverterBenchmark"

# Run only dictionary benchmarks
./gradlew :dd-java-agent:agent-profiling:profiling-otel:jmh -PjmhIncludes="DictionaryTableBenchmark"
```

## Benchmark Filtering

Use `-PjmhIncludes` to filter benchmarks by name (supports regex):

```bash
# Run specific benchmark class
./gradlew jmh -PjmhIncludes="JfrToOtlpConverterBenchmark"

# Run specific benchmark method
./gradlew jmh -PjmhIncludes=".*convertJfrToOtlp"

# Run all string-related benchmarks
./gradlew jmh -PjmhIncludes=".*internString.*"
```

**Estimated time**:
- Full suite: ~40 minutes
- Single benchmark class: ~5-15 minutes depending on parameters

## Benchmark Categories

### 1. DictionaryTableBenchmark

Tests deduplication performance for all dictionary tables:

- `internString` - String interning (most frequent)
- `internFunction` - Function metadata interning
- `internLocation` - Stack frame location interning
- `internStack` - Call stack deduplication
- `internLink` - Trace context link interning
- `internAttribute` - Attribute key-value interning

**Parameters**:
- `uniqueEntries`: 100, 1000, 10000 (pool size)
- `hitRate`: 0.0 (all unique), 0.5 (50% cache hits), 0.95 (95% cache hits)

### 2. StackTraceConversionBenchmark

Tests end-to-end JFR stack trace conversion to OTLP format:

- `convertStackTrace` - Full conversion pipeline

**Parameters**:
- `stackDepth`: 5, 15, 30, 50 (frames per stack)
- `uniqueStacks`: 1, 10, 100 (deduplication factor)

### 3. ProtobufEncoderBenchmark

Tests low-level protobuf encoding primitives:

- `writeVarint*` - Variable-length integer encoding (small, medium, large, very large)
- `writeFixed64` - Fixed 64-bit encoding
- `writeString*` - UTF-8 string encoding (short, medium, long)
- `writeBytes*` - Byte array encoding (short, medium, long)
- `writeNestedMessage*` - Nested message encoding (simple, complex)
- `writeTypical*` - Realistic combined operations (sample, location, function)
- `toByteArray` - Final serialization overhead

### 4. JfrToOtlpConverterBenchmark

Tests full end-to-end JFR to OTLP conversion performance:

- `convertJfrToOtlp` - Complete conversion pipeline including:
  - JFR file parsing
  - Event processing
  - Dictionary deduplication
  - OTLP protobuf encoding

**Parameters**:
- `eventCount`: 50, 500, 5000 (number of events in JFR recording)
- `stackDepth`: 10, 50, 100 (frames per stack trace)
- `uniqueContexts`: 100, 1000 (number of unique trace contexts)

**Use case**: Measures real-world conversion throughput with realistic workloads

## Advanced Usage

### Running Specific Benchmarks

```bash
# Run only string interning benchmarks
./gradlew jmh -PjmhIncludes=".*internString"

# Run end-to-end converter benchmark
./gradlew jmh -PjmhIncludes="JfrToOtlpConverterBenchmark"

# Run specific method across all benchmark classes
./gradlew jmh -PjmhIncludes=".*convertStackTrace"
```

### Customizing JMH Parameters

To customize warmup iterations, measurement iterations, or other JMH parameters, you need to modify the `jmh { }` block in `build.gradle.kts` directly. The me.champeau.jmh plugin doesn't support command-line parameter overrides for most settings.

Alternatively, run the JMH JAR directly for full control:

```bash
# Build the JMH JAR
./gradlew :dd-java-agent:agent-profiling:profiling-otel:jmhJar

# Run with custom JMH options
java -jar dd-java-agent/agent-profiling/profiling-otel/build/libs/profiling-otel-jmh.jar \
  JfrToOtlpConverterBenchmark \
  -wi 3 -i 5 -f 1
```

Common JMH CLI options:
- `-wi N` - Warmup iterations (default: 3)
- `-i N` - Measurement iterations (default: 5)
- `-f N` - Forks (default: 1)
- `-l` - List all benchmarks
- `-lp` - List benchmarks with parameters

## Performance Expectations

Based on typical hardware (M1/M2 Mac or modern x86_64):

- **String interning**: 8-26 ops/µs (cold to warm cache)
- **Function interning**: 10-25 ops/µs
- **Stack interning**: 15-30 ops/µs
- **Stack conversion**: Scales linearly with stack depth
- **Protobuf encoding**: Varint 50-100 ops/µs, strings 10-50 ops/µs
- **End-to-end conversion** (JfrToOtlpConverterBenchmark - measured on Apple M3 Max):
  - **50 events**: 156-428 ops/s (2.3-6.4 ms/op) depending on stack depth
  - **500 events**: 38-130 ops/s (7.7-26.0 ms/op) depending on stack depth
  - **5000 events**: 3.5-30 ops/s (33.7-289 ms/op) depending on stack depth
  - **Key factors**: Stack depth (10-100 frames) is the dominant performance factor, ~60% throughput reduction for 10x depth increase
  - **Scaling**: Linear with event count, minimal impact from unique context count (100 vs 1000)

## Interpreting Results

- **Higher ops/µs = Better** (throughput mode)
- **Cold cache (hitRate=0.0)**: Tests worst-case deduplication performance
- **Warm cache (hitRate=0.95)**: Tests best-case lookup performance
- **Real-world typically**: Between 50-80% hit rate for most applications

## Adding New Benchmarks

1. Add `@Benchmark` method to appropriate class
2. Use `@Param` for parameterized testing
3. Follow JMH best practices (use Blackhole, avoid dead code elimination)
4. Document expected performance characteristics

## References

- [JMH Documentation](https://github.com/openjdk/jmh)
- [JMH Samples](https://github.com/openjdk/jmh/tree/master/jmh-samples/src/main/java/org/openjdk/jmh/samples)
