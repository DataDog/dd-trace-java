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
│   ├── string_table[]      - interned strings
│   ├── function_table[]    - function metadata
│   ├── location_table[]    - stack frame locations
│   ├── mapping_table[]     - binary/library mappings
│   ├── stack_table[]       - call stacks (arrays of location indices)
│   ├── link_table[]        - trace context links
│   └── attribute_table[]   - key-value attributes
│
└── resource_profiles[]
    └── scope_profiles[]
        └── profiles[]
            ├── sample_type: ValueType
            ├── period_type: ValueType
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
└── (future: converter, writer classes)
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

**AttributeTable**: Supports STRING, BOOL, INT, DOUBLE value types. Key includes (keyIndex, valueType, value, unitIndex).

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

### Phase 2: JFR Parsing & CPU Profile (In Progress)

(To be documented as implementation progresses)

## Testing Strategy

- **Unit Tests**: Each dictionary table and encoder method tested independently
- **Integration Tests**: End-to-end conversion with JMC JFR Writer API for creating test recordings
- **Round-trip Validation**: Verify protobuf output can be parsed correctly

## Dependencies

- `jafar-parser` - JFR parsing library
- `internal-api` - RecordingData abstraction
- `libs.bundles.jmc` - JMC libraries for test JFR creation (test scope)
- `libs.bundles.junit5` - Testing framework (test scope)
