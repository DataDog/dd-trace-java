# Profcheck Integration Analysis

This document analyzes the feasibility of integrating OpenTelemetry's `profcheck` tool for validating OTLP profiles produced by our JFR-to-OTLP converter.

## What is Profcheck?

**Profcheck** is an OpenTelemetry conformance checker for the OTLP Profiles format, currently in PR review at: https://github.com/open-telemetry/sig-profiling/pull/12

### Key Features

The tool validates:
- **Dictionary tables**: All tables (mapping, location, function, link, string, attribute, stack)
- **Index validity**: Ensures all indices reference valid entries
- **Reference integrity**: Checks cross-references between data structures
- **Sample consistency**: Validates sample values and timestamps
- **Time range boundaries**: Verifies timestamps are within profile time range
- **Data completeness**: Ensures required fields are present

### How It Works

```bash
# Simple CLI tool
profcheck <protobuf-file>

# Reads binary protobuf ProfilesData
# Runs comprehensive validation
# Outputs: "conformance checks passed" or detailed errors
```

## Integration Feasibility: **HIGH** ✅

### Pros

1. **Simple CLI Interface**
   - Single command: `profcheck <file>`
   - Reads standard protobuf files (our converter already produces these)
   - Clear pass/fail output with detailed error messages

2. **No Code Changes Required**
   - Written in Go, runs as standalone binary
   - Works with our existing protobuf output
   - Can be integrated into CI/CD pipeline

3. **Comprehensive Validation**
   - Checks all dictionary tables
   - Validates index references
   - Ensures spec compliance
   - Currently in active development with OTLP community

4. **Easy to Adopt**
   ```bash
   # Build profcheck
   cd tools/profcheck
   go build -o profcheck profcheck.go check.go

   # Use with our converter
   ./gradlew convertJfr --args="input.jfr output.pb"
   profcheck output.pb
   ```

### Cons

1. **Not Yet Merged**
   - Still in PR review (https://github.com/open-telemetry/sig-profiling/pull/12)
   - May undergo API changes before merge
   - Need to track upstream changes

2. **Go Dependency**
   - Requires Go toolchain to build
   - Need to vendor or download pre-built binary
   - Cross-platform build considerations

3. **Limited Scope**
   - Only validates structure, not semantics
   - Doesn't validate actual profiling data correctness
   - Won't catch domain-specific issues (e.g., invalid stack traces)

## Recommended Integration Approach

### Phase 1: Docker-Based Testing (✅ IMPLEMENTED)

Profcheck is now available as a **Docker-based validation tool**:

```bash
# Convert JFR to OTLP
./gradlew :dd-java-agent:agent-profiling:profiling-otel:convertJfr \
  --args="recording.jfr output.pb"

# Build profcheck Docker image (one-time)
./gradlew :dd-java-agent:agent-profiling:profiling-otel:buildProfcheck

# Validate with profcheck
./gradlew :dd-java-agent:agent-profiling:profiling-otel:validateOtlp \
  -PotlpFile=output.pb
```

**OR use Docker directly**:

```bash
# Build once (from project root)
docker build -f docker/Dockerfile.profcheck -t profcheck:latest .

# Validate
docker run --rm -v $(pwd):/data:ro profcheck:latest /data/output.pb
```

**Benefits**:
- ✅ No Go installation required
- ✅ Reproducible environment
- ✅ Works on any platform with Docker
- ✅ Easy to integrate into CI/CD
- ✅ Automatically fetches latest profcheck from PR branch

### Phase 2: CI/CD Integration (After PR Merge)

Once profcheck is merged upstream, integrate into CI:

```yaml
# .github/workflows/validate-otlp.yml
name: OTLP Validation

on: [push, pull_request]

jobs:
  validate-otlp:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Install Go
        uses: actions/setup-go@v4
        with:
          go-version: '1.21'

      - name: Install profcheck
        run: |
          git clone https://github.com/open-telemetry/sig-profiling.git
          cd sig-profiling/tools/profcheck
          go build -o $HOME/bin/profcheck .
          echo "$HOME/bin" >> $GITHUB_PATH

      - name: Generate test profile
        run: |
          ./gradlew :dd-java-agent:agent-profiling:profiling-otel:convertJfr \
            --args="test-data/sample.jfr test-output.pb"

      - name: Validate with profcheck
        run: profcheck test-output.pb
```

### Phase 3: Test Integration (Long-term)

Add profcheck validation to existing tests:

```gradle
// build.gradle.kts
tasks.register<Exec>("validateOtlpWithProfcheck") {
  group = "verification"
  description = "Validate OTLP output using profcheck"

  dependsOn("test")

  commandLine("profcheck", "build/test-results/sample-output.pb")
}

tasks.named("check") {
  dependsOn("validateOtlpWithProfcheck")
}
```

## Current Implementation Gaps

Based on profcheck validation, our converter should ensure:

1. ✅ **String table starts with empty string** (index 0)
2. ✅ **All indices are valid** (within bounds)
3. ✅ **Dictionary zero values** (first entry must be zero/empty)
4. ✅ **Time range consistency** (timestamps within profile bounds)
5. ⚠️  **Attribute indices** (we don't currently use attributes)
6. ⚠️  **Mapping table** (we don't currently populate mappings)

### Known Gaps to Address

Our current implementation doesn't populate:
- Mapping table (binary/library information)
- Attribute indices (resource/scope attributes)

These are optional per spec but profcheck validates them if present.

## Example Validation Output

### Valid Profile
```
$ profcheck output.pb
output.pb: conformance checks passed
```

### Invalid Profile
```
$ profcheck output.pb
output.pb: conformance checks failed: profile 0: sample[5]:
  timestamps_unix_nano[0]=1700000000 is outside profile time range
  [1700000100, 1700060100]
```

## Recommendations

### Immediate Actions

1. **Manual Testing**: Use profcheck locally to validate converter output
2. **Document Usage**: Add profcheck instructions to CLI.md
3. **Track Upstream**: Monitor PR #12 for merge status

### After PR Merge

1. **CI Integration**: Add profcheck validation to GitHub Actions
2. **Test Data**: Create test JFR files with known-good OTLP output
3. **Regression Testing**: Run profcheck on every converter change

### Long-term

1. **Vendoring**: Consider vendoring profcheck or pre-built binaries
2. **Test Suite**: Expand converter tests to cover all profcheck validations
3. **Documentation**: Document profcheck validation in ARCHITECTURE.md

## Conclusion

**YES, we can easily use profcheck to validate our OTLP profiles.**

- ✅ Simple CLI tool with clear interface
- ✅ No code changes required
- ✅ Works with our existing protobuf output
- ✅ Comprehensive validation coverage
- ✅ Can be integrated into CI/CD

**Recommended**: Start using profcheck manually now, integrate into CI after upstream PR merges.
