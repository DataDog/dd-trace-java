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
- **End-to-end conversion** (JfrToOtlpConverterBenchmark - measured on Apple M3 Max, JDK 21.0.5):

| Event Count | Stack Depth | Unique Contexts | Throughput (ops/s) | Time per Operation |
|-------------|-------------|-----------------|--------------------|--------------------|
| 50          | 10          | 100             | 344-370 ops/s      | 2.7-2.9 ms/op      |
| 50          | 10          | 1000            | 344-428 ops/s      | 2.3-2.9 ms/op      |
| 50          | 50          | 100             | 154-213 ops/s      | 4.7-6.5 ms/op      |
| 50          | 50          | 1000            | 165-203 ops/s      | 4.9-6.1 ms/op      |
| 50          | 100         | 100             | 160 ops/s          | 6.2 ms/op          |
| 50          | 100         | 1000            | 156 ops/s          | 6.4 ms/op          |
| 500         | 10          | 100             | 130-137 ops/s      | 7.3-7.7 ms/op      |
| 500         | 10          | 1000            | 122-127 ops/s      | 7.9-8.2 ms/op      |
| 500         | 50          | 100             | 62-66 ops/s        | 15.2-16.1 ms/op    |
| 500         | 50          | 1000            | 61-67 ops/s        | 14.9-16.3 ms/op    |
| 500         | 100         | 100             | 38-41 ops/s        | 24.4-26.3 ms/op    |
| 500         | 100         | 1000            | 40-41 ops/s        | 24.3-25.0 ms/op    |
| 5000        | 10          | 100             | 29.7-30.6 ops/s    | 32.7-33.7 ms/op    |
| 5000        | 10          | 1000            | 29.0-29.0 ops/s    | 34.5-34.5 ms/op    |
| 5000        | 50          | 100             | 8.1-8.2 ops/s      | 122-123 ms/op      |
| 5000        | 50          | 1000            | 7.9-8.6 ops/s      | 116-126 ms/op      |
| 5000        | 100         | 100             | 3.9-4.0 ops/s      | 250-257 ms/op      |
| 5000        | 100         | 1000            | 3.8-3.9 ops/s      | 256-263 ms/op      |

  - **Key factors**:
    - Stack depth (10-100 frames) is the dominant performance factor, ~60% throughput reduction per 10x depth increase
    - Event count scales linearly (10x events = ~10x processing time)
    - Unique context count (100 vs 1000) has minimal impact on throughput
  - **Deduplication efficiency**: High hit rates on dictionary tables (strings, functions, stacks) provide effective compression but marginal performance gains

## Interpreting Results

- **Higher ops/µs = Better** (throughput mode)
- **Cold cache (hitRate=0.0)**: Tests worst-case deduplication performance
- **Warm cache (hitRate=0.95)**: Tests best-case lookup performance
- **Real-world typically**: Between 50-80% hit rate for most applications

## Profiling Benchmarks

JMH supports built-in profilers to identify CPU and allocation hotspots:

```bash
# Run with CPU stack profiling and GC allocation profiling
./gradlew :dd-java-agent:agent-profiling:profiling-otel:jmh \
  -PjmhIncludes="JfrToOtlpConverterBenchmark" \
  -PjmhProfile=true
```

This enables:
- **Stack profiler**: CPU sampling to identify hot methods
- **GC profiler**: Allocation rate tracking and GC overhead measurement

### Profiling Results (December 2024)

Profiling the end-to-end converter revealed the actual performance bottlenecks:

**CPU Time Distribution** (from stack profiler on deep stack workloads):

1. **JFR File I/O (~17-22%)**:
   - `DirectByteBuffer.get`: 3.5-17% (peaks with deep stacks)
   - `RecordingStreamReader.readVarint`: 1.6-5.5%
   - `MutableConstantPools.getConstantPool`: 0.4-1.1%
   - This is the jafar-parser library reading JFR binary format

2. **Protobuf Encoding (~3-7%)**:
   - `ProtobufEncoder.writeVarint/writeVarintField`: 0.6-5.8%
   - `ProtobufEncoder.writeNestedMessage`: 0.5-0.9%
   - Fundamental serialization cost

3. **Conversion Logic (~2-4%)**:
   - `JfrToOtlpConverter.convertFrame`: 0.3-1.9%
   - `JfrToOtlpConverter.encodeSample`: 0.4-1.3%
   - `JfrToOtlpConverter.encodeDictionary`: 0.2-0.6%

4. **Dictionary Operations (~1-2%)**:
   - `Arrays.hashCode`: 0.5-1.4% (HashMap key hashing)
   - `LocationTable.intern`: 0.3-0.5%
   - **Dictionary operations are already well-optimized**

**Allocation Data**:
- 5-20 MB per operation (varies with stack depth/event count)
- Allocation rate: 1.4-1.9 GB/sec
- GC overhead: 2-5% of total time

**Key Insights**:
- Dictionary operations account for only ~1-2% of runtime (not the bottleneck)
- JFR parsing dominates at ~20% (external dependency, I/O bound)
- Stack depth is the dominant performance factor due to O(n) frame processing
- Modern JVM escape analysis already optimizes temporary allocations
- HashMap lookups are ~10-20ns, completely dominated by I/O overhead

**Performance Optimization Attempts**:
- Attempted Phase 1 optimizations targeting dictionary operations showed no improvement (-7% to +6%, within noise)
- Optimization attempt: `tryGetExisting()` to avoid string concatenation - Result: Added allocation overhead (2 FunctionKey allocations instead of 1)
- Profiling proved that intuition-based optimizations were targeting the wrong bottleneck

**Conclusion**: The 60% throughput reduction with 10x stack depth increase is fundamentally due to processing 10x more frames (O(n) with depth), not inefficient data structures. Further optimization would require:
1. Reducing JFR parsing overhead (external library)
2. Optimizing protobuf varint encoding (diminishing returns)
3. Batch processing to amortize per-operation overhead

## Adding New Benchmarks

1. Add `@Benchmark` method to appropriate class
2. Use `@Param` for parameterized testing
3. Follow JMH best practices (use Blackhole, avoid dead code elimination)
4. Document expected performance characteristics
5. Use profiling (`-PjmhProfile=true`) to validate optimization impact

## References

- [JMH Documentation](https://github.com/openjdk/jmh)
- [JMH Samples](https://github.com/openjdk/jmh/tree/master/jmh-samples/src/main/java/org/openjdk/jmh/samples)
