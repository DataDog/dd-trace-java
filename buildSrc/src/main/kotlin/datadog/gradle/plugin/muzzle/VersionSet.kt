package datadog.gradle.plugin.muzzle

import org.eclipse.aether.version.Version
import kotlin.random.Random

class VersionSet(
  versions: Collection<Version>,
  private val isEligible: (Version) -> Boolean = { true }
) {
  private val orderedVersions = versions.sorted()
  private val sortedVersions = versions.map { ParsedVersion(it) }.sorted()
  private val groups by lazy { sortedVersions.groupBy { it.majorMinor } }
  private val eligibility = mutableMapOf<Version, Boolean>()

  val lowestEligibleVersion: Version?
    get() = orderedVersions.firstOrNull(::eligible)

  val highestEligibleVersion: Version?
    get() = orderedVersions.asReversed().firstOrNull(::eligible)

  /** Find eligible boundaries without checking the intervening patch releases. */
  val lowAndHighForMajorMinor: List<Version>
    get() {
      val resultSet = sortedSetOf<ParsedVersion>()
      for (group in groups.values) {
        val lowIndex = group.indexOfFirst { eligible(it.version) }
        if (lowIndex < 0) continue
        resultSet.add(group[lowIndex])

        highestInGroup(group, lowIndex)?.let { resultSet.add(it) }
      }
      return resultSet.map { it.version }
    }

  /** Sample before looking up ages; only deferred candidates require extra backfill probes. */
  internal fun sampleEligibleBoundaries(limit: Int, random: Random): Set<Version> {
    val lowest = lowestEligibleVersion ?: return emptySet()
    val highest = highestEligibleVersion!!
    val candidates = sortedSetOf<ParsedVersion>()
    groups.values.forEach { group ->
      candidates.add(group.first())
      candidates.add(group.first { it.compareTo(group.last()) == 0 })
    }
    val selected = linkedSetOf(lowest, highest)
    // The original sampler discards from the front of a shuffled boundary list, protecting extrema.
    for (candidate in candidates.shuffled(random).asReversed()) {
      if (selected.size >= limit) break
      val group = groups.getValue(candidate.majorMinor)
      val replacement = if (candidate == group.first()) {
        group.firstOrNull { eligible(it.version) }
      } else {
        highestInGroup(group)
      }
      replacement?.let { selected.add(it.version) }
    }
    return selected
  }

  private fun highestInGroup(group: List<ParsedVersion>, lowIndex: Int = -1): ParsedVersion? {
    var high: ParsedVersion? = null
    for (index in group.lastIndex downTo lowIndex + 1) {
      val candidate = group[index]
      if (high != null && candidate.compareTo(high) != 0) break
      if (eligible(candidate.version)) {
        // Preserve the first eligible spelling of equivalent parsed versions.
        high = candidate
      }
    }
    return high
  }

  private fun eligible(version: Version): Boolean = eligibility.getOrPut(version) { isEligible(version) }

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
