package datadog.gradle.plugin.muzzle

import java.io.Serializable

internal data class VersionSubstitution(
  val requestedGroup: String,
  val requestedModule: String,
  val requestedVersion: String,
  val targetGroup: String,
  val targetModule: String,
  val targetVersion: String,
) : Serializable {
  val requestedNotation: String
    get() = "$requestedGroup:$requestedModule:$requestedVersion"

  val targetNotation: String
    get() = "$targetGroup:$targetModule:$targetVersion"

  fun matches(group: String, module: String, version: String?): Boolean =
    requestedGroup == group && requestedModule == module && requestedVersion == version

  companion object {
    fun parse(requested: String, target: String): VersionSubstitution {
      val requestedParts = parseCoordinate(requested)
      val targetParts = parseCoordinate(target)
      return VersionSubstitution(
        requestedGroup = requestedParts[0],
        requestedModule = requestedParts[1],
        requestedVersion = requestedParts[2],
        targetGroup = targetParts[0],
        targetModule = targetParts[1],
        targetVersion = targetParts[2],
      )
    }

    private fun parseCoordinate(coordinate: String): List<String> {
      val parts = coordinate.split(":")
      require(parts.size == 3 && parts.none { it.isBlank() }) {
        "Expected dependency coordinate in 'group:module:version' form but got '$coordinate'"
      }
      return parts
    }
  }
}
