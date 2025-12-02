# OTLP Profiling Benchmarks

This module includes JMH microbenchmarks to measure the performance of critical hot-path operations.

## Quick Start

Run the essential benchmarks (takes ~5 minutes):

```bash
./gradlew :dd-java-agent:agent-profiling:profiling-otel:jmh
```

## Benchmark Suites

### Default (Fast) - `./gradlew jmh`

Runs only the most critical hot-path benchmarks with realistic parameters:

- **Dictionary interning**: `internString`, `internFunction`, `internStack`
- **Stack trace conversion**: `convertStackTrace`
- **Parameters**: 1000 unique entries, 0% and 95% hit rates, stack depths 15 and 30

**Estimated time**: ~5 minutes
**Use case**: Quick validation during development

### Full Suite - `./gradlew jmhFull`

Runs all benchmarks with comprehensive parameter combinations:

- All dictionary table operations (String, Function, Location, Stack, Link, Attribute)
- All protobuf encoder primitives (varint, fixed64, strings, bytes, nested messages)
- Stack trace conversion with varying depths and deduplication
- **Parameters**: 3 uniqueEntries values × 3 hitRate values × multiple stack depths

**Estimated time**: ~40 minutes
**Use case**: Comprehensive performance analysis before release

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

## Running Specific Benchmarks

```bash
# Run only string interning benchmarks
./gradlew jmh -Pjmh.includes=".*internString"

# Run with specific parameters
./gradlew jmh -Pjmh.includes=".*internString" -Pjmh.params="uniqueEntries=1000,hitRate=0.95"

# Reduce warmup/measurement iterations for faster runs (less accurate)
./gradlew jmh -Pjmh.warmupIterations=1 -Pjmh.measurementIterations=1
```

## Performance Expectations

Based on typical hardware (M1/M2 Mac or modern x86_64):

- **String interning**: 8-26 ops/µs (cold to warm cache)
- **Function interning**: 10-25 ops/µs
- **Stack interning**: 15-30 ops/µs
- **Stack conversion**: Scales linearly with stack depth
- **Protobuf encoding**: Varint 50-100 ops/µs, strings 10-50 ops/µs

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
