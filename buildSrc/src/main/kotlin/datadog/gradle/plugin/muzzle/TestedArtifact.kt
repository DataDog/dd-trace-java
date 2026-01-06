package datadog.gradle.plugin.muzzle

import org.eclipse.aether.version.Version

// Changed from internal to public for cross-file accessibility
internal data class TestedArtifact(
  val instrumentation: String,
  val group: String,
  val module: String,
  val lowVersion: Version,
  val highVersion: Version
) {
  fun key(): String = "$instrumentation:$group:$module"
}
