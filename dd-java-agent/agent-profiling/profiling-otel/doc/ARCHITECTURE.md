# OTLP Profiles Writer - Architecture & Implementation Journal

## Overview

This module provides a JFR to OTLP/profiles format converter. It reads JFR recordings via the `RecordingData` abstraction and produces OTLP-compliant profile data in both binary protobuf and JSON formats.

## OTLP Profiles Format

Based on: https://github.com/open-telemetry/opentelemetry-proto/blob/main/opentelemetry/proto/profiles/v1development/profiles.proto

### Key Architectural Concepts

1. **Dictionary-based Compression**: OTLP profiles use shared dictionary tables to minimize wire size. All repeated data (strings, functions, locations, stacks, links, attributes) is stored once in dictionary tables and referenced by integer indices.

2. **Index 0 Semantics**: In all dictionary tables, index 0 is reserved for "null/unset" values. Index 0 should never be dereferenced - it represents the absence of a value.

3. **Sample Identity**: A sample's identity is the tuple `{stack_index, set_of(attribute_indices), link_index}`. Samples with the same identity should be aggregated.

### Message Hierarchy

```
ProfilesData
├── dictionary: ProfilesDictionary (shared across all profiles)
│   ├── string_table[]      - interned strings (used for function names, filenames,
│   │                         attribute keys, attribute units, value type names, etc.)
│   ├── function_table[]    - function metadata (nameIndex, systemNameIndex, filenameIndex → string_table)
│   ├── location_table[]    - stack frame locations (functionIndex → function_table)
│   ├── mapping_table[]     - binary/library mappings
│   ├── stack_table[]       - call stacks (arrays of locationIndex → location_table)
│   ├── link_table[]        - trace context links (traceId, spanId as raw bytes)
│   └── attribute_table[]   - key-value attributes (keyIndex, unitIndex → string_table;
│                             string values stored as raw strings, NOT indices)
│
└── resource_profiles[]
    └── scope_profiles[]
        └── profiles[]
            ├── sample_type: ValueType (type, unit → string_table)
            ├── period_type: ValueType (type, unit → string_table)
            ├── samples[]
            │   ├── stack_index      -> stack_table
            │   ├── attribute_indices[] -> attribute_table
            │   ├── link_index       -> link_table
            │   ├── values[]
            │   └── timestamps_unix_nano[]
            └── time_unix_nano, duration_nano, profile_id, etc.
```

## Package Structure

```
com.datadog.profiling.otel/
├── dictionary/           # Dictionary table implementations
│   ├── StringTable       # String interning
│   ├── FunctionTable     # Function deduplication
│   ├── LocationTable     # Stack frame deduplication
│   ├── StackTable        # Call stack deduplication
│   ├── LinkTable         # Trace link deduplication
│   └── AttributeTable    # Attribute deduplication
│
├── proto/                # Protobuf encoding
│   ├── ProtobufEncoder   # Wire format encoder
│   └── OtlpProtoFields   # Field number constants
│
├── jfr/                  # JFR event type definitions
│   ├── ExecutionSample   # CPU profiling (datadog.ExecutionSample)
│   ├── MethodSample      # Wall-clock profiling (datadog.MethodSample)
│   ├── ObjectSample      # Allocation profiling (datadog.ObjectSample)
│   ├── JavaMonitorEnter  # Lock contention (jdk.JavaMonitorEnter)
│   ├── JavaMonitorWait   # Monitor wait (jdk.JavaMonitorWait)
│   ├── JfrStackTrace     # Stack trace container
│   ├── JfrStackFrame     # Individual stack frame
│   ├── JfrMethod         # Method descriptor
│   └── JfrClass          # Class descriptor
│
├── JfrToOtlpConverter    # Main converter (JFR -> OTLP)
└── OtlpProfileWriter     # Profile writer interface
```

## JFR Event to OTLP Mapping

| JFR Event Type | OTLP Profile Type | Value Type | Unit |
|----------------|-------------------|------------|------|
| `datadog.ExecutionSample` | cpu | count | samples |
| `datadog.MethodSample` | wall | count | samples |
| `datadog.ObjectSample` | alloc-samples | bytes | bytes |
| `jdk.JavaMonitorEnter` | lock-contention | duration | nanoseconds |
| `jdk.JavaMonitorWait` | lock-contention | duration | nanoseconds |

## Implementation Details

### Phase 1: Core Infrastructure (Completed)

#### Dictionary Tables

All dictionary tables follow a common pattern:
- Index 0 reserved for null/unset (pre-populated in constructor)
- `intern()` method returns existing index or adds new entry
- `get()` method retrieves entry by index
- `reset()` method clears table to initial state
- HashMap-based deduplication for O(1) lookup

**StringTable**: Simple string interning. Null and empty strings map to index 0.

**FunctionTable**: Functions identified by composite key (nameIndex, systemNameIndex, filenameIndex, startLine). All indices reference StringTable.

**LocationTable**: Locations represent stack frames. Key is (mappingIndex, address, functionIndex, line, column). Supports multiple Line entries for inlined functions.

**StackTable**: Stacks are arrays of location indices. Uses Arrays.hashCode/equals for array-based key comparison. Makes defensive copies of input arrays.

**LinkTable**: Links connect samples to trace spans. Stores 16-byte traceId and 8-byte spanId. Provides convenience method for 64-bit DD trace/span IDs.

**AttributeTable**: Supports STRING, BOOL, INT, DOUBLE value types. Key includes (keyIndex, valueType, value, unitIndex). Important: Per OTLP spec, attribute keys and units are stored as indices into StringTable, but string VALUES are stored as raw String objects (not indices). This matches the protobuf `AnyValue.string_value` field which holds raw strings. Only the keyIndex and unitIndex reference the StringTable.

#### ProtobufEncoder

Hand-coded protobuf wire format encoder without external dependencies:

- **Wire Types**: VARINT (0), FIXED64 (1), LENGTH_DELIMITED (2), FIXED32 (5)
- **Varint Encoding**: Variable-length integers, 7 bits per byte, MSB indicates continuation
- **ZigZag Encoding**: For signed varints, maps negative numbers to positive
- **Fixed Encoding**: Little-endian for fixed32/fixed64
- **Length-Delimited**: Length prefix (varint) followed by content
- **Nested Messages**: Written to temporary buffer to compute length first

Key methods:
- `writeVarint()`, `writeFixed64()`, `writeFixed32()`
- `writeTag()` - combines field number and wire type
- `writeString()`, `writeBytes()` - length-delimited
- `writeNestedMessage()` - for sub-messages
- `writePackedVarintField()`, `writePackedFixed64Field()` - for repeated fields

#### OtlpProtoFields

Constants for all OTLP protobuf field numbers, organized by message type. Enables type-safe field references without magic numbers.

### Phase 2: JFR Parsing & Event Conversion (Completed)

#### TypedJafarParser Integration

Uses the typed JafarParser API (from `io.btrace:jafar-parser`) for efficient JFR event parsing. The typed parser generates implementations at runtime for interfaces annotated with `@JfrType`.

**JFR Type Interfaces** (`com.datadog.profiling.otel.jfr`):

Each interface maps to a specific JFR event type:

```java
@JfrType("datadog.ExecutionSample")
public interface ExecutionSample {
    long startTime();
    JfrStackTrace stackTrace();
    long spanId();
    long localRootSpanId();
}
```

Supporting types for stack trace traversal:
- `JfrStackTrace` - contains array of `JfrStackFrame`
- `JfrStackFrame` - references `JfrMethod`, line number, bytecode index
- `JfrMethod` - references `JfrClass`, method name, descriptor
- `JfrClass` - class name, package info

#### JfrToOtlpConverter

Main converter class that:

1. **Parses JFR stream** using `TypedJafarParser`:
   - Creates temp file from input stream (parser requires file path)
   - Registers handlers for each event type
   - Runs parser to process all events

2. **Builds dictionary tables** during parsing:
   - Strings → `StringTable`
   - Methods → `FunctionTable`
   - Stack frames → `LocationTable`
   - Call stacks → `StackTable`
   - Trace context → `LinkTable`
   - Profile type labels → `AttributeTable`

3. **Aggregates samples** by identity `{stack_index, attribute_indices, link_index}`:
   - Samples with same identity are merged
   - Values (count, duration, bytes) are summed
   - Timestamps are collected

4. **Encodes output** using `ProtobufEncoder`:
   - First encodes dictionary (ProfilesDictionary)
   - Then encodes samples with references to dictionary
   - Outputs binary protobuf format

#### Profile Type Discrimination

Samples are tagged with profile type via attributes:
- `profile.type` attribute with values: `cpu`, `wall`, `alloc-samples`, `lock-contention`
- Each event handler sets appropriate type when creating sample

#### Trace Context Integration

For events with span context (ExecutionSample, MethodSample, ObjectSample):
- Extracts `spanId` and `localRootSpanId` from JFR event
- Creates Link entry in `LinkTable`
- Links samples to originating trace spans

### Phase 3-4: Additional Event Types & Trace Context (Completed)

All event types implemented in Phase 2:
- CPU profiling via `datadog.ExecutionSample`
- Wall-clock via `datadog.MethodSample`
- Allocation via `datadog.ObjectSample` (includes allocation size)
- Lock contention via `jdk.JavaMonitorEnter` and `jdk.JavaMonitorWait` (includes duration)

Trace context fully integrated via LinkTable for span correlation.

### Phase 5: JSON Output & Integration Tests (Completed)

#### JSON Output Format

The converter now supports both binary protobuf and JSON text output via an enum-based API:

```java
public enum Kind {
  /** Protobuf binary format (default). */
  PROTO,
  /** JSON text format. */
  JSON
}

// Convert to protobuf (default)
byte[] protobuf = converter.addFile(jfrFile, start, end).convert();
// OR explicitly
byte[] protobuf = converter.addFile(jfrFile, start, end).convert(Kind.PROTO);

// Convert to JSON
byte[] json = converter.addFile(jfrFile, start, end).convert(Kind.JSON);
```

**JSON Encoding Implementation**:
- Uses DataDog's `JsonWriter` component (`components/json`)
- Produces human-readable JSON matching the OTLP protobuf structure
- Binary IDs (trace_id, span_id, profile_id) encoded as hex strings
- Dictionary tables fully serialized in the `dictionary` section
- Samples reference dictionary entries by index (same as protobuf)

**Example JSON output structure**:
```json
{
  "resource_profiles": [{
    "scope_profiles": [{
      "profiles": [{
        "sample_type": {"type": 1, "unit": 2},
        "samples": [{
          "stack_index": 3,
          "attribute_indices": [4, 5],
          "link_index": 1,
          "values": [100],
          "timestamps_unix_nano": [1234567890000000]
        }],
        "time_unix_nano": 1234567890000000,
        "duration_nano": 60000000000,
        "profile_id": "0123456789abcdef"
      }]
    }]
  }],
  "dictionary": {
    "string_table": ["", "cpu", "samples", ...],
    "function_table": [...],
    "location_table": [...],
    "stack_table": [...],
    "link_table": [...],
    "attribute_table": [...]
  }
}
```

#### Integration Tests

Smoke tests implemented using JMC JFR Writer API:
- `JfrToOtlpConverterSmokeTest.java` - 8 tests covering all event types
- Tests verify both protobuf and JSON output
- Events tested: ExecutionSample, MethodSample, ObjectSample, JavaMonitorEnter
- Multi-file conversion and converter reuse validated

### Phase 5.5: Performance Benchmarking (Completed)

JMH microbenchmarks implemented in `src/jmh/java/com/datadog/profiling/otel/benchmark/`:

1. **DictionaryTableBenchmark** - Dictionary interning performance
   - Tests StringTable, FunctionTable, StackTable interning
   - Measures cold (unique entries) vs warm (cache hits) performance
   - Parameterized by entry count and hit rate

2. **StackTraceConversionBenchmark** - JFR stack trace conversion overhead
   - End-to-end conversion of JFR events to OTLP samples
   - Parameterized by stack depth and unique stack count
   - Measures throughput in samples/second

3. **ProtobufEncoderBenchmark** - Wire format encoding performance
   - Measures varint, fixed64, string, and nested message encoding
   - Tests packed repeated field encoding
   - Validates low-level encoder efficiency

**Benchmark Execution**:
```bash
./gradlew :dd-java-agent:agent-profiling:profiling-otel:jmh
```

**Key Performance Characteristics**:
- Dictionary interning: ~8-26 ops/µs (cold to warm cache)
- Stack trace conversion: Scales linearly with stack depth
- Protobuf encoding: Minimal overhead for varint/fixed encoding

### Phase 6: OTLP Compatibility Testing & Validation (In Progress)

#### Objective

Establish comprehensive validation to ensure generated OTLP profiles comply with OpenTelemetry specifications and are compatible with OTLP collectors/receivers.

#### Validation Rules

Based on [OTLP profiles.proto v1development](https://github.com/open-telemetry/opentelemetry-proto/blob/main/opentelemetry/proto/profiles/v1development/profiles.proto):

1. **Index 0 Semantics**: All dictionary tables must have index 0 reserved for null/unset values
2. **No Duplicates**: Dictionary entries should be unique by value
3. **No Orphans**: Unreferenced dictionary items should not exist
4. **Sample Identity**: `{stack_index, set_of(attribute_indices), link_index}` tuple defines sample uniqueness
5. **Timestamp Consistency**: Sample timestamps must fall within profile time bounds `[time_unix_nano, time_unix_nano + duration_nano)`
6. **Valid References**: All sample indices must reference valid dictionary entries
7. **Non-zero Trace Context**: Link trace/span IDs must be non-zero when present

#### Current Testing Gaps

✅ **Existing Coverage**:
- ProtobufEncoder unit tests (26 tests) for wire format correctness
- Dictionary table unit tests for basic functionality
- Smoke tests for end-to-end conversion
- Performance benchmarks

❌ **Missing Coverage**:
- Index 0 reservation validation across all dictionaries
- Dictionary uniqueness constraint verification
- Orphaned entry detection
- Timestamp consistency validation
- Round-trip validation (encode → parse → compare)
- Interoperability testing with OTLP collectors
- Semantic validation of OTLP requirements

#### Implementation Plan

**Phase 6A: Validation Utilities (Mandatory)**

Create validation infrastructure:

1. **`OtlpProfileValidator.java`** - Static validation methods:
   - `validateDictionaries()` - Check index 0, uniqueness, references
   - `validateSamples()` - Check timestamps, indices, consistency
   - `validateProfile()` - Comprehensive validation of entire profile

2. **`ValidationResult.java`** - Result object with:
   - Pass/fail status
   - List of validation errors with details
   - Warnings for non-critical issues

3. **`OtlpProfileRoundTripTest.java`** - Round-trip validation:
   - Generate profile with known data
   - Parse back the encoded protobuf
   - Validate structure matches expectations
   - Verify no data loss or corruption

4. **Integration with existing tests** - Add validation calls to:
   - Dictionary table unit tests
   - `JfrToOtlpConverterSmokeTest`
   - Any new profile generation tests

**Phase 6B: External Tool Integration (Optional)**

1. **Buf CLI Integration** - Schema linting:
   - Add `bufLint` Gradle task
   - Validate against official OTLP proto files
   - Detect breaking changes

2. **OpenTelemetry Collector Integration** - Interoperability testing:
   - Docker Compose setup with OTel Collector
   - Send generated profiles to collector endpoint
   - Verify acceptance and processing
   - Check exported data format

#### Success Criteria

1. ✅ All dictionary tables have index 0 validation
2. ✅ No duplicate entries in dictionaries (verified by tests)
3. ✅ All sample indices reference valid entries (verified by tests)
4. ✅ Timestamp consistency validated
5. ✅ Round-trip validation passes
6. ✅ Documentation updated with validation approach

#### Trade-offs

**Validation Strictness**: Validation is strict in tests (fail on violations), but optional in production (can be enabled via feature flag for debugging).

**Performance Impact**: Validation has overhead and should:
- Always run in tests
- Be optional in production
- Skip in performance-critical paths

**External Tools**: Buf CLI and OpenTelemetry Collector integration are documented but not required for builds (optional for enhanced validation).

## Testing Strategy

- **Unit Tests**: Each dictionary table and encoder method tested independently
  - 26 ProtobufEncoder tests for wire format correctness
  - Dictionary table tests for interning, deduplication, and index 0 handling
- **Smoke Tests**: End-to-end conversion with JMC JFR Writer API for creating test recordings
  - `JfrToOtlpConverterSmokeTest` with 8 test cases covering all event types
  - Tests both protobuf and JSON output formats
- **Performance Benchmarks**: JMH microbenchmarks for hot-path validation
  - Dictionary interning performance (cold vs warm cache)
  - Stack trace conversion throughput
  - Protobuf encoding overhead
- **Validation Tests** (Phase 6): Compliance with OTLP specification
  - Dictionary constraint validation (index 0, uniqueness, no orphans)
  - Sample consistency validation (timestamps, references)
  - Round-trip validation (encode → parse → verify)

## Dependencies

- `jafar-parser` - JFR parsing library (snapshot from Sonatype)
- `internal-api` - RecordingData abstraction
- `components:json` - DataDog's JSON serialization component (for JSON output)
- `libs.bundles.jmc` - JMC libraries for test JFR creation (test scope)
- `libs.bundles.junit5` - Testing framework (test scope)
- `libs.jmc.flightrecorder.writer` - JMC JFR writer API for test recording generation (test scope)
