# Instrumentation Naming Convention Plugin

A Gradle plugin that validates naming conventions for instrumentation modules under `dd-java-agent/instrumentation`.

## Naming Rules

The plugin enforces the following rules:

1. **Version or suffix**: Module names must end with either:
   - A version number (e.g., `2.0`, `3.1`, `4.0.5`)
   - One of the configured suffixes (default: `-common`, `-stubs`)

2. **Parent directory inclusion**: Module names must include the parent directory name
   - Example: `couchbase/couchbase-2.0` ✓ (module name contains "couchbase")
   - Example: `couchbase/foo-2.0` ✗ (module name doesn't contain "couchbase")

## Usage

### Apply the plugin

In your root `build.gradle` or `build.gradle.kts`:

```kotlin
plugins {
  id("dd-trace-java.instrumentation-naming")
}
```

### Run the validation

```bash
./gradlew checkInstrumentationNaming
```

### Configuration

You can configure the plugin with custom settings:

```kotlin
instrumentationNaming {
  // Optional: specify a different instrumentations directory
  instrumentationsDir.set("path/to/instrumentations")

  // Optional: exclude specific modules from validation
  exclusions.set(listOf(
    "http-url-connection",
    "sslsocket",
    "classloading"
  ))

  // Optional: configure allowed suffixes (default: ["-common", "-stubs"])
  suffixes.set(listOf(
    "-common",
    "-stubs",
    "-utils"
  ))
}
```

## Examples

### Valid module names:
- `couchbase/couchbase-2.0` ✓ (ends with version)
- `couchbase/couchbase-2.6` ✓ (ends with version)
- `couchbase/couchbase-3.1` ✓ (ends with version)
- `kafka/kafka-common` ✓ (ends with -common suffix)
- `apache-httpclient/apache-httpclient-4.0` ✓ (ends with version)

### Invalid module names:
- `couchbase/foo-2.0` ✗ (doesn't contain parent name "couchbase")
- `kafka/kafka` ✗ (missing version or allowed suffix)
- `kafka/kafka-latest` ✗ (not a valid version number or allowed suffix)

## Integration with CI

Add the task to your verification pipeline:

```kotlin
tasks.named("check") {
  dependsOn("checkInstrumentationNaming")
}
```

This ensures naming conventions are validated on every build.
