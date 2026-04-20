# Publishing dd-trace-java Snapshots with ddprof SNAPSHOT Dependency

## Overview

This feature allows publishing dd-trace-java snapshot versions that depend on a ddprof SNAPSHOT version with an incremented minor version.

**ddprof Version Calculation:** Current ddprof version `X.Y.Z` → Dependency becomes `X.(Y+1).0-SNAPSHOT`

**Example:** ddprof `1.34.4` → Uses dependency `1.35.0-SNAPSHOT`

### Version Qualification

To avoid overwriting standard snapshot artifacts, builds with `-PddprofUseSnapshot` will have a `-ddprof` qualifier added to their version:

- Standard snapshot: `1.58.0-SNAPSHOT`
- With ddprof snapshot: `1.58.0-ddprof-SNAPSHOT`

This ensures that both versions can coexist in Maven Central Snapshots repository without conflicts.

## Local Usage

### Testing Dependency Resolution

To verify that the ddprof snapshot version is correctly calculated and applied:

```bash
./gradlew -PddprofUseSnapshot :dd-java-agent:ddprof-lib:dependencies --configuration runtimeClasspath
```

Look for the output:
- `Using ddprof snapshot version: X.Y.0-SNAPSHOT`
- `Modified version for dd-trace-java: 1.58.0-SNAPSHOT -> 1.58.0-ddprof-SNAPSHOT`
- `ddprof-lib: Using ddprof SNAPSHOT version X.Y.0-SNAPSHOT`
- Dependency resolution showing: `com.datadoghq:ddprof:X.Y.Z -> X.(Y+1).0-SNAPSHOT`

### Building with ddprof Snapshot

To build the project with the ddprof snapshot dependency:

```bash
./gradlew build -PddprofUseSnapshot
```

### Publishing to Maven Central Snapshots

To publish artifacts with the ddprof snapshot dependency:

```bash
./gradlew publishToSonatype -PddprofUseSnapshot -PskipTests
```

**Note:** You must have the required credentials configured:
- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `GPG_PRIVATE_KEY`
- `GPG_PASSWORD`

## GitLab CI Usage

### Manual Job Trigger

A GitLab CI job named `deploy_snapshot_with_ddprof_snapshot` is available for manual execution.

**To trigger:**
1. Navigate to the pipeline in GitLab CI
2. Find the `deploy_snapshot_with_ddprof_snapshot` job in the `publish` stage
3. Click the manual play button to trigger it

**What it does:**
- Builds dd-trace-java with `-PddprofUseSnapshot`
- Publishes to Maven Central Snapshots repository
- Produces artifacts with the ddprof snapshot dependency

**When to use:**
- Testing integration with unreleased ddprof features
- Validating compatibility before ddprof release
- Creating test builds for early adopters

## Implementation Details

### Files Modified

1. **`gradle/ddprof-override.gradle`** - Calculates snapshot version and stores it for convention plugins
2. **`build.gradle.kts`** - Applies the ddprof-snapshot configuration
3. **`buildSrc/src/main/kotlin/dd-trace-java.profiling-ddprof-override.gradle.kts`** - Convention plugin for dependency override
4. **`buildSrc/src/main/kotlin/datadog/gradle/plugin/version/TracerVersionPlugin.kt`** - Enhanced to support version qualifiers
5. **`dd-java-agent/ddprof-lib/build.gradle`** - Applies the convention plugin
6. **`.gitlab-ci.yml`** - New CI job for snapshot publishing

### How It Works

1. The Gradle property `-PddprofUseSnapshot` activates the feature
2. The configuration reads `gradle/libs.versions.toml` to get the current ddprof version
3. Version is parsed using regex: `ddprof = "X.Y.Z"`
4. Snapshot version is calculated: `X.(Y+1).0-SNAPSHOT` and stored in `rootProject.ext.ddprofSnapshotVersion`
5. **The dd-trace-java version is modified** to add a `-ddprof` qualifier:
   - `1.58.0-SNAPSHOT` → `1.58.0-ddprof-SNAPSHOT`
   - This prevents overwriting standard snapshot artifacts
   - Users can also explicitly set this via `-PtracerVersion.qualifier=ddprof`
6. The `dd-trace-java.profiling-ddprof-override` convention plugin is applied to projects that depend on ddprof
7. The convention plugin overrides ddprof dependencies to use the snapshot version
8. The build and publish proceed with the modified version and overridden dependency

### Dependency Resolution Override

The override is applied via a convention plugin to only the projects that need it:

```kotlin
// Convention plugin: dd-trace-java.profiling-ddprof-override
if (rootProject.hasProperty("ddprofUseSnapshot")) {
  val ddprofSnapshotVersion = rootProject.property("ddprofSnapshotVersion").toString()

  configurations.all {
    resolutionStrategy.eachDependency {
      if (requested.group == "com.datadoghq" && requested.name == "ddprof") {
        useVersion(ddprofSnapshotVersion)
        because("Using ddprof snapshot version for integration testing")
      }
    }
  }
}
```

Projects apply this plugin explicitly in their build files:

```groovy
plugins {
  id "dd-trace-java.profiling-ddprof-override"
}
```

This ensures that only projects that actually depend on ddprof are affected by the override.

## Limitations

- Only works with semantic versioning in format `X.Y.Z`
- Requires ddprof SNAPSHOT to be published to Maven Central Snapshots repository
- Cannot override local JAR files specified with `-Pddprof.jar=/path/to/jar`

## Troubleshooting

### "Could not find com.datadoghq:ddprof:X.Y.0-SNAPSHOT"

**Cause:** The calculated ddprof snapshot version doesn't exist in Maven Central Snapshots.

**Solutions:**
- Verify ddprof has published the snapshot version
- Check Maven Central Snapshots repository: https://central.sonatype.com/repository/maven-snapshots/
- Wait for ddprof CI to complete if a new snapshot is being published

### Version not being overridden

**Cause:** The property might not be correctly set or parsed.

**Solutions:**
- Ensure you're using `-PddprofUseSnapshot` (not `-DddprofUseSnapshot`)
- Check Gradle output for "Using ddprof snapshot version" message
- Run with `--info` flag to see detailed dependency resolution logs
