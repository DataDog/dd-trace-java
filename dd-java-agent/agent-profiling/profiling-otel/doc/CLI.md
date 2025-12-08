# JFR to OTLP Converter CLI

Command-line tool for converting JFR recordings to OTLP profiles format for testing and validation.

## Quick Start

### Using the Convenience Script

The simplest way to convert JFR files:

```bash
cd dd-java-agent/agent-profiling/profiling-otel
./convert-jfr.sh recording.jfr output.pb
```

The script automatically handles compilation and classpath. See [Convenience Script](#convenience-script) section below.

### Using Gradle Directly

Convert a JFR file to OTLP protobuf format:

```bash
./gradlew :dd-java-agent:agent-profiling:profiling-otel:convertJfr --args="recording.jfr output.pb"
```

Convert to JSON for human inspection:

```bash
./gradlew :dd-java-agent:agent-profiling:profiling-otel:convertJfr --args="--json recording.jfr output.json"
```

## Usage

```bash
./gradlew :dd-java-agent:agent-profiling:profiling-otel:convertJfr --args="[options] input.jfr [input2.jfr ...] output"
```

### Options

- `--json` - Output JSON format instead of protobuf (compact by default)
- `--pretty` - Pretty-print JSON output with indentation (use with `--json`)
- `--include-payload` - Include original JFR payload in output (increases size significantly)
- `--help` - Show help message

### Examples

#### Basic Conversion

Convert single JFR to protobuf:

```bash
./gradlew :dd-java-agent:agent-profiling:profiling-otel:convertJfr \
  --args="recording.jfr output.pb"
```

#### JSON Output for Inspection

Output compact JSON for processing:

```bash
./gradlew :dd-java-agent:agent-profiling:profiling-otel:convertJfr \
  --args="--json recording.jfr output.json"
```

Output pretty-printed JSON for human inspection:

```bash
./gradlew :dd-java-agent:agent-profiling:profiling-otel:convertJfr \
  --args="--json --pretty recording.jfr output.json"

# Inspect with jq
cat output.json | jq '.dictionary.string_table | length'
cat output.json | jq '.resource_profiles[0].scope_profiles[0].profiles[] | .sample_type'
```

#### Merge Multiple Recordings

Combine multiple JFR files into a single OTLP output:

```bash
./gradlew :dd-java-agent:agent-profiling:profiling-otel:convertJfr \
  --args="recording1.jfr recording2.jfr recording3.jfr merged.pb"
```

This is useful for:
- Merging recordings from different time periods
- Combining CPU and allocation profiles
- Testing dictionary deduplication across files

#### Include Original Payload

Include the original JFR data in the OTLP output:

```bash
./gradlew :dd-java-agent:agent-profiling:profiling-otel:convertJfr \
  --args="--include-payload recording.jfr output.pb"
```

**Note**: This significantly increases output size (typically 2-3x) as it embeds the entire JFR file(s) in the `original_payload` field.

## Output Analysis

The CLI prints conversion statistics:

```
Converting 1 JFR file(s) to OTLP format...
  Adding: /path/to/recording.jfr
Conversion complete!
  Output: /path/to/output.pb
  Format: PROTO
  Size: 45.2 KB
  Time: 127 ms
```

With `--include-payload`:

```
Converting 1 JFR file(s) to OTLP format...
  Adding: /path/to/recording.jfr
Conversion complete!
  Output: /path/to/output.pb
  Format: PROTO
  Size: 125.7 KB
  Time: 134 ms
  Input size: 89.3 KB
  Compression: 140.8%
```

**Note**: When including the original payload, the output may be *larger* than the input due to protobuf overhead. The primary benefit of original_payload is preserving the raw data for alternative processing, not compression.

## Inspecting JSON Output

The JSON output contains the complete OTLP structure:

```json
{
  "resource_profiles": [{
    "scope_profiles": [{
      "profiles": [{
        "sample_type": { "type_strindex": 1, "unit_strindex": 2 },
        "samples": [
          { "stack_index": 1, "link_index": 2, "values": [1], "timestamps_unix_nano": [1234567890000000] }
        ],
        "time_unix_nano": 1234567800000000000,
        "duration_nano": 60000000000,
        "profile_id": "a1b2c3d4..."
      }]
    }]
  }],
  "dictionary": {
    "location_table": [...],
    "function_table": [...],
    "link_table": [...],
    "string_table": ["", "cpu", "samples", "com.example.Class", ...],
    "stack_table": [...]
  }
}
```

Key fields to inspect:

```bash
# Count samples by profile type
cat output.json | jq '.resource_profiles[0].scope_profiles[0].profiles[] | "\(.sample_type.type_strindex): \(.samples | length)"'

# Show dictionary sizes
cat output.json | jq '.dictionary | {strings: (.string_table | length), functions: (.function_table | length), locations: (.location_table | length), stacks: (.stack_table | length)}'

# Show first 10 stack frames
cat output.json | jq '.dictionary.string_table[1:10]'

# Find deepest stack
cat output.json | jq '.dictionary.stack_table | max_by(.location_indices | length)'
```

## Testing Real JFR Files

To test with production JFR recordings:

1. **Generate test recording**:
   ```bash
   # Start profiling
   jcmd <pid> JFR.start name=test duration=60s filename=test.jfr

   # Wait for recording
   sleep 60

   # Convert to OTLP
   ./gradlew :dd-java-agent:agent-profiling:profiling-otel:convertJfr \
     --args="test.jfr output.pb"
   ```

2. **Use existing recording**:
   ```bash
   # Find JFR files
   find /tmp -name "*.jfr" 2>/dev/null

   # Convert the most recent
   latest=$(ls -t /tmp/*.jfr | head -1)
   ./gradlew :dd-java-agent:agent-profiling:profiling-otel:convertJfr \
     --args="--json $latest output.json"
   ```

3. **Compare formats**:
   ```bash
   # Original JFR size
   ls -lh recording.jfr

   # OTLP protobuf size
   ./gradlew :dd-java-agent:agent-profiling:profiling-otel:convertJfr \
     --args="recording.jfr output.pb"
   ls -lh output.pb

   # OTLP JSON size (larger due to text encoding)
   ./gradlew :dd-java-agent:agent-profiling:profiling-otel:convertJfr \
     --args="--json recording.jfr output.json"
   ls -lh output.json
   ```

## Performance Testing

For performance benchmarks, use the JMH benchmarks instead:

```bash
# Run end-to-end conversion benchmark
./gradlew :dd-java-agent:agent-profiling:profiling-otel:jmh \
  -PjmhIncludes="JfrToOtlpConverterBenchmark"
```

See [BENCHMARKS.md](BENCHMARKS.md) for details.

## Troubleshooting

### "Input file not found"

Ensure the JFR file path is correct and accessible:

```bash
ls -l recording.jfr
```

### "Error parsing JFR file"

The JFR file may be corrupted or incomplete. Validate with:

```bash
jfr print --events recording.jfr
```

### Gradle task not found

Ensure you're using the full task path:

```bash
./gradlew :dd-java-agent:agent-profiling:profiling-otel:convertJfr --args="..."
```

### Out of memory

For very large JFR files, increase heap:

```bash
./gradlew :dd-java-agent:agent-profiling:profiling-otel:convertJfr \
  --args="large.jfr output.pb" \
  --max-workers=1 \
  -Dorg.gradle.jvmargs="-Xmx2g"
```

## Direct Java Execution

For scripting or CI/CD, you can run the CLI directly after building:

```bash
# Build the project
./gradlew :dd-java-agent:agent-profiling:profiling-otel:jar

# Run directly with java
java -cp "dd-java-agent/agent-profiling/profiling-otel/build/libs/*:$(find . -name 'jafar-parser*.jar'):$(find internal-api -name '*.jar'):$(find components/json -name '*.jar')" \
  com.datadog.profiling.otel.JfrToOtlpConverterCLI \
  recording.jfr output.pb
```

**Note**: Managing the classpath manually is complex. The Gradle task is recommended.

## Validating Output with Profcheck

OpenTelemetry's `profcheck` tool can validate that generated OTLP profiles conform to the specification:

```bash
# Convert JFR to OTLP
./gradlew :dd-java-agent:agent-profiling:profiling-otel:convertJfr \
  --args="recording.jfr output.pb"

# Build profcheck Docker image (one-time)
./gradlew :dd-java-agent:agent-profiling:profiling-otel:buildProfcheck

# Validate with profcheck
./gradlew :dd-java-agent:agent-profiling:profiling-otel:validateOtlp \
  -PotlpFile=output.pb
# Output: "output.pb: conformance checks passed"

# OR use Docker directly
docker run --rm -v $(pwd):/data:ro profcheck:latest /data/output.pb
```

See [PROFCHECK_INTEGRATION.md](PROFCHECK_INTEGRATION.md) for:
- Profcheck integration details
- Integration with CI/CD
- Validation coverage details

## Convenience Script

The `convert-jfr.sh` script provides a simpler interface that wraps the Gradle task:

### Location

```bash
dd-java-agent/agent-profiling/profiling-otel/convert-jfr.sh
```

### Usage

```bash
./convert-jfr.sh [options] <input.jfr> [input2.jfr ...] <output.pb|output.json>
```

### Options

- `--json` - Output in JSON format instead of protobuf
- `--pretty` - Pretty-print JSON output (implies --json)
- `--include-payload` - Include original JFR payload in OTLP output
- `--diagnostics` - Show detailed diagnostics (file sizes, conversion time)
- `--help` - Show help message

### Examples

Basic conversion:
```bash
./convert-jfr.sh recording.jfr output.pb
```

Convert to JSON:
```bash
./convert-jfr.sh --json recording.jfr output.json
```

Convert to pretty-printed JSON:
```bash
./convert-jfr.sh --pretty recording.jfr output.json
```

Include original JFR payload:
```bash
./convert-jfr.sh --include-payload recording.jfr output.pb
```

Combine multiple files:
```bash
./convert-jfr.sh file1.jfr file2.jfr file3.jfr merged.pb
```

Show detailed diagnostics:
```bash
./convert-jfr.sh --diagnostics recording.jfr output.pb
```

Output:
```
[INFO] Converting JFR to OTLP format...
[DIAG] Input: recording.jfr (89.3KB)
[DIAG] Total input size: 89.3KB
[SUCCESS] Conversion completed successfully!
[INFO] Output file: output.pb (45.2KB)

[DIAG] === Conversion Diagnostics ===
[DIAG] Wall time: 127.3ms
[DIAG] Output size: 45.2KB
[DIAG] Size ratio: 50.6% of input
[DIAG] Savings: 44.1KB (49.4% reduction)
```

### Features

- **Automatic compilation**: Compiles code if needed before conversion
- **Simplified interface**: No need to remember Gradle task paths
- **Colored output**: Visual feedback for success/errors
- **File size reporting**: Shows output file size after conversion
- **Diagnostics mode**: Detailed metrics including input/output sizes, conversion time, and compression ratio
- **Error handling**: Clear error messages if conversion fails

### Script Output

```
[INFO] Converting JFR to OTLP format...
[INFO] Arguments: recording.jfr output.pb
[SUCCESS] Conversion completed successfully!
[INFO] Output file: output.pb (45K)
```

### When to Use

- **Quick conversions**: When you want the simplest interface
- **Development workflow**: Rapid iteration during development
- **Testing**: Quick validation of JFR files
- **Scripting**: Easy to use in shell scripts

Use the Gradle task directly when you need:
- Integration with build system
- Custom Gradle configuration
- CI/CD pipeline integration

## See Also

- [ARCHITECTURE.md](ARCHITECTURE.md) - Converter design and implementation details
- [BENCHMARKS.md](BENCHMARKS.md) - Performance benchmarks and profiling
- [PROFCHECK_INTEGRATION.md](PROFCHECK_INTEGRATION.md) - OTLP validation with profcheck
- [../README.md](../README.md) - Module overview
