package datadog.gradle.plugin.muzzle

import org.eclipse.aether.version.Version
import java.util.SortedSet

class VersionSet(versions: Collection<Version>) {
  private val sortedVersions: SortedSet<ParsedVersion> = sortedSetOf()

  init {
      versions.forEach { sortedVersions.add(ParsedVersion(it)) }
  }

  val lowAndHighForMajorMinor: List<Version>
    get() {
      var previous: ParsedVersion? = null
      var currentMajorMinor = -1
      val resultSet = sortedSetOf<ParsedVersion>()
      for (parsed in sortedVersions) {
        val majorMinor = parsed.majorMinor
        if (majorMinor != currentMajorMinor) {
          previous?.let { resultSet.add(it) }
          previous = null
          resultSet.add(parsed)
          currentMajorMinor = majorMinor
        } else {
          previous = parsed
        }
      }
      previous?.let { resultSet.add(it) }
      return resultSet.map { it.version }
    }

  internal class ParsedVersion(val version: Version) : Comparable<ParsedVersion> {
    companion object {
      private val dotPattern = Regex("\\.")
      private const val VERSION_SHIFT = 12
    }
    val versionNumber: Long
    val ending: String
    init {
      var versionString = version.toString()
      var ending = ""
      val dash = versionString.indexOf('-')
      if (dash > 0) {
        ending = versionString.substring(dash + 1)
        versionString = versionString.substring(0, dash)
      }
      val groups = versionString.split(dotPattern).toMutableList()
      var versionNumber = 0L
      var iteration = 0
      while (iteration < 3) {
        versionNumber = versionNumber shl VERSION_SHIFT
        if (groups.isNotEmpty() && groups[0].toIntOrNull() != null) {
          versionNumber += groups.removeAt(0).toLong()
        }
        iteration++
      }
      if (groups.isNotEmpty()) {
        val rest = groups.joinToString(".")
        ending = if (ending.isEmpty()) rest else "$rest-$ending"
      }
      this.versionNumber = versionNumber
      this.ending = ending
    }
    val majorMinor: Int
      get() = (versionNumber shr VERSION_SHIFT).toInt()
    override fun compareTo(other: ParsedVersion): Int {
      val diff = versionNumber - other.versionNumber
      return if (diff != 0L) diff.toInt() else ending.compareTo(other.ending)
    }
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is ParsedVersion) return false
      return versionNumber == other.versionNumber && ending == other.ending
    }
    override fun hashCode(): Int = (versionNumber * 31 + ending.hashCode()).toInt()
  }
}
