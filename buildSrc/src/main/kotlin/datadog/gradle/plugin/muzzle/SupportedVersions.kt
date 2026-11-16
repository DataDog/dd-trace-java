package datadog.gradle.plugin.muzzle

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import datadog.gradle.plugin.ci.isLatestDepTest
import datadog.gradle.plugin.testJvmConstraints.isTestJvmAllowed
import org.eclipse.aether.util.version.GenericVersionScheme
import org.gradle.api.JavaVersion
import java.io.File

/**
 * Builds the `supported_versions.json` file published by the one-pipeline `generate-supported-versions` job.
 *
 * Each instrumentation project writes a [SupportedVersionsFragment] per library and integration, keyed by the
 * Java major versions its tests ran on. The aggregation merges the fragments of all projects, then groups
 * the Java versions that share the same supported range and tested versions into one `versions` entry.
 */
internal object SupportedVersions {
  /** The build directory holding the supported versions. */
  const val SUPPORTED_VERSIONS_RESULTS = "supported-versions"

  private val versionScheme = GenericVersionScheme()
  private val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

  /** A `muzzle { pass { ... } }` directive and the instrumentations it validates. */
  data class Directive(
    val libraryName: String,
    val versions: String,
    val javaVersion: Int?,
    /** Instrumentation name to whether it is enabled by default. */
    val integrations: Map<String, Boolean>,
    val skipVersions: Set<String> = emptySet(),
  )

  /** Muzzle ranges and tested library versions for one Java major version. */
  data class RuntimeSupport(val ranges: Set<String>, val tested: Set<String>)

  data class SupportedVersionsFragment(
    val libraryName: String,
    val integrationName: String,
    val autoInstrumented: Boolean,
    /** Java major version to its support. */
    val runtimes: Map<Int, RuntimeSupport>,
  )

  /** Java major versions `:instrumentationTest` runs on, see the `.test_matrix` in `.gitlab-ci.yml`. */
  internal val TEST_JAVA_VERSIONS = listOf(8, 11, 17, 21, 25)

  /** Java major versions `:instrumentationLatestDepTest` runs on, see `test_inst_latest` in `.gitlab-ci.yml`. */
  internal val LATEST_DEP_TEST_JAVA_VERSIONS = listOf(8, 17, 21, 25)

  /** The JVM constraints of a test task named after its source set. */
  data class TestSuite(
    val name: String,
    val minJavaVersion: JavaVersion?,
    val maxJavaVersion: JavaVersion?,
    val includeJdk: List<String>,
    val excludeJdk: List<String>,
    val forceJdk: List<String>,
  )

  /**
   * Maps each test suite's runtime classpath configuration to the Java major versions CI runs the suite on.
   * Forked test tasks share the constraints and classpath of their suite, so the suites cover them.
   */
  fun testedRuntimes(suites: List<TestSuite>): Map<String, Set<Int>> =
    suites.associate { suite ->
      val ciJavaVersions = if (isLatestDepTest(suite.name)) LATEST_DEP_TEST_JAVA_VERSIONS else TEST_JAVA_VERSIONS
      "${suite.name}RuntimeClasspath" to ciJavaVersions.filter { javaVersion ->
        isTestJvmAllowed(
          javaVersion.toString(),
          { JavaVersion.toVersion(javaVersion) },
          suite.includeJdk,
          suite.excludeJdk,
          suite.forceJdk,
          suite.minJavaVersion,
          suite.maxJavaVersion,
        )
      }.toSet()
    }

  /**
   * Parses a Gradle `gradle.lockfile` into `group:module` to version to the configurations resolving it.
   */
  fun parseLockfile(text: String): Map<String, Map<String, Set<String>>> {
    val result = mutableMapOf<String, MutableMap<String, Set<String>>>()
    text.lineSequence()
      .map { it.trim() }
      .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("empty=") }
      .forEach { line ->
        val coordinates = line.substringBefore('=').split(':')
        if (coordinates.size != 3) return@forEach
        val configurations = line.substringAfter('=', "").split(',').filter { it.isNotEmpty() }.toSet()
        result.getOrPut("${coordinates[0]}:${coordinates[1]}") { mutableMapOf() }[coordinates[2]] = configurations
      }
    return result
  }

  /**
   * Builds the fragments of one instrumentation project. Tested versions outside the supported range are
   * not reported, and are passed to [warn] so the muzzle range or the tests can be fixed.
   *
   * @param directives The project's pass directives.
   * @param lockfile The project's parsed lockfile, see [parseLockfile].
   * @param testedRuntimes Each test runtime classpath configuration to the Java major versions CI runs it on.
   */
  fun fragments(
    directives: List<Directive>,
    lockfile: Map<String, Map<String, Set<String>>>,
    testedRuntimes: Map<String, Set<Int>>,
    warn: (String) -> Unit = {},
  ): List<SupportedVersionsFragment> {
    val javaVersions = testedRuntimes.values.flatten().toSortedSet()
    return directives
      .flatMap { directive -> directive.integrations.map { (integration, enabled) -> Triple(directive, integration, enabled) } }
      .groupBy { (directive, integration, _) -> directive.libraryName to integration }
      .mapNotNull { (key, matches) ->
        val (libraryName, integrationName) = key
        val outOfRange = mutableSetOf<String>()
        val runtimes = javaVersions.mapNotNull { javaVersion ->
          val ranges = matches
            .map { it.first }
            .filter { it.javaVersion == null || it.javaVersion <= javaVersion }
            .map { excludeVersions(it.versions, it.skipVersions) }
            .toSet()
          if (ranges.isEmpty()) return@mapNotNull null
          val (tested, untested) = lockfile[libraryName].orEmpty()
            .filterValues { configurations -> configurations.any { javaVersion in testedRuntimes[it].orEmpty() } }
            .keys
            .partition { inRange(ranges, it) }
          outOfRange += untested
          // Only report the Java versions a test ran the library on, like the other tracers.
          if (tested.isEmpty()) return@mapNotNull null
          javaVersion to RuntimeSupport(ranges, tested.toSet())
        }.toMap()
        if (outOfRange.isNotEmpty()) {
          warn(
            "$libraryName ($integrationName): tested versions ${sortVersions(outOfRange)} are outside the muzzle " +
              "pass range, so they are not reported. Fix the muzzle range or the tested versions."
          )
        }
        if (runtimes.isEmpty()) return@mapNotNull null
        SupportedVersionsFragment(libraryName, integrationName, matches.any { it.third }, runtimes)
      }
  }

  /** The library and integration pairs of [directives] that no fragment reports, because nothing tests them. */
  fun untested(directives: List<Directive>, fragments: List<SupportedVersionsFragment>): Set<Pair<String, String>> {
    val reported = fragments.map { it.libraryName to it.integrationName }.toSet()
    return directives.flatMap { directive -> directive.integrations.keys.map { directive.libraryName to it } }.toSet() - reported
  }

  /** Merges the fragments that several projects wrote for the same library and integration. */
  fun merge(fragments: List<SupportedVersionsFragment>): List<SupportedVersionsFragment> =
    fragments
      .groupBy { it.libraryName to it.integrationName }
      .map { (key, group) ->
        val runtimes = mutableMapOf<Int, RuntimeSupport>()
        group.forEach { fragment ->
          fragment.runtimes.forEach { (javaVersion, support) ->
            runtimes.merge(javaVersion, support) { a, b -> RuntimeSupport(a.ranges + b.ranges, a.tested + b.tested) }
          }
        }
        SupportedVersionsFragment(key.first, key.second, group.any { it.autoInstrumented }, runtimes)
      }
      .sortedWith(compareBy({ it.integrationName }, { it.libraryName }))

  /** Converts merged fragments to the `supported_versions.json` entries. */
  fun entries(fragments: List<SupportedVersionsFragment>): List<Map<String, Any>> =
    fragments.map { fragment ->
      // Java versions with the same supported range and tested versions share one entry.
      val versions = fragment.runtimes.entries
        .groupBy({ (_, support) -> supportedRange(support.ranges) to sortVersions(support.tested) }, { it.key })
        .map { (support, javaVersions) -> javaVersions.sorted() to support }
        .sortedBy { (javaVersions, _) -> javaVersions.first() }
        .map { (javaVersions, support) ->
          linkedMapOf(
            "testedRuntimes" to mapOf("java" to javaVersions.map { it.toString() }),
            "supportedRange" to support.first,
            "tested" to support.second,
          )
        }
      linkedMapOf(
        "libraryName" to fragment.libraryName,
        "integrationName" to fragment.integrationName,
        "autoInstrumented" to fragment.autoInstrumented,
        "versions" to versions,
      )
    }

  /** Merges Maven ranges into one Maven range union without overlapping intervals. */
  fun supportedRange(ranges: Set<String>): String {
    val intervals = ranges.flatMap { intervals(it) }.sortedWith(Comparator { a, b -> compareLower(a, b) })
    val merged = mutableListOf<Interval>()
    intervals.forEach { next ->
      val last = merged.lastOrNull()
      if (last != null && overlaps(last, next)) {
        if (compareUpper(next, last) > 0) {
          merged[merged.size - 1] = last.copy(upper = next.upper, upperInclusive = next.upperInclusive)
        }
      } else {
        merged += next
      }
    }
    if (merged.size == 1 && merged[0].lower == null && merged[0].upper == null) return "*"
    return merged.joinToString(",") { it.toString() }
  }

  /** Removes [versions] from a Maven [range], like `[4,8)` without `4.4.19` is `[4,4.4.19),(4.4.19,8)`. */
  fun excludeVersions(range: String, versions: Set<String>): String {
    if (versions.isEmpty()) return range
    return intervals(range).flatMap { interval ->
      sortVersions(versions).filter { contains(interval, it) }.fold(listOf(interval)) { parts, version ->
        val last = parts.last()
        val split = listOf(last.copy(upper = version, upperInclusive = false), last.copy(lower = version, lowerInclusive = false))
        parts.dropLast(1) + split.filterNot { isEmpty(it) }
      }
    }.joinToString(",") { it.toString() }
  }

  /** Whether [version] is in any of the Maven [ranges]. */
  fun inRange(ranges: Set<String>, version: String): Boolean =
    ranges.any { range -> intervals(range).any { contains(it, version) } }

  fun sortVersions(versions: Collection<String>): List<String> =
    versions.sortedBy { versionScheme.parseVersion(it) }

  /** A project's fragments and the pairs it doesn't report because nothing tests them. */
  data class ProjectReport(val fragments: List<SupportedVersionsFragment>, val untested: Set<Pair<String, String>>)

  fun writeReport(file: File, report: ProjectReport) {
    file.parentFile.mkdirs()
    mapper.writeValue(
      file,
      linkedMapOf(
        "fragments" to report.fragments.map { fragment ->
          linkedMapOf(
            "libraryName" to fragment.libraryName,
            "integrationName" to fragment.integrationName,
            "autoInstrumented" to fragment.autoInstrumented,
            "runtimes" to fragment.runtimes.mapValues { (_, support) ->
              linkedMapOf("ranges" to support.ranges.sorted(), "tested" to sortVersions(support.tested))
            },
          )
        },
        "untested" to report.untested.sortedWith(compareBy({ it.second }, { it.first })).map { listOf(it.first, it.second) },
      ),
    )
  }

  fun readReport(file: File): ProjectReport {
    val root = mapper.readTree(file)
    val fragments = root["fragments"].map { node ->
      SupportedVersionsFragment(
        node["libraryName"].asText(),
        node["integrationName"].asText(),
        node["autoInstrumented"].asBoolean(),
        node["runtimes"].fields().asSequence().associate { (javaVersion, support) ->
          javaVersion.toInt() to RuntimeSupport(support["ranges"].texts(), support["tested"].texts())
        },
      )
    }
    return ProjectReport(fragments, root["untested"].map { it[0].asText() to it[1].asText() }.toSet())
  }

  fun writeEntries(file: File, entries: List<Map<String, Any>>) {
    file.parentFile.mkdirs()
    file.writeText(mapper.writeValueAsString(entries) + "\n")
  }

  private fun JsonNode.texts(): Set<String> = map { it.asText() }.toSet()

  private val INTERVAL = Regex("""[\[(][^\])]*[\])]""")

  /** A Maven range interval, where a `null` bound is unbounded. */
  private data class Interval(val lower: String?, val lowerInclusive: Boolean, val upper: String?, val upperInclusive: Boolean) {
    override fun toString(): String =
      if (lower != null && lower == upper) {
        "[$lower]"
      } else {
        "${if (lower != null && lowerInclusive) "[" else "("}${lower.orEmpty()},${upper.orEmpty()}${if (upper != null && upperInclusive) "]" else ")"}"
      }
  }

  /** Parses a Maven range, which can be a union like `[1.9,1.16],[5,6)` or a bare version, the only one allowed. */
  private fun intervals(range: String): List<Interval> =
    INTERVAL.findAll(range).map { parseInterval(it.value) }.toList().ifEmpty { listOf(Interval(range.trim(), true, range.trim(), true)) }

  private fun contains(interval: Interval, version: String): Boolean {
    val lower = interval.lower?.let { compareVersions(version, it) }
    val upper = interval.upper?.let { compareVersions(version, it) }
    return (lower == null || lower > 0 || (lower == 0 && interval.lowerInclusive)) &&
      (upper == null || upper < 0 || (upper == 0 && interval.upperInclusive))
  }

  private fun isEmpty(interval: Interval): Boolean {
    if (interval.lower == null || interval.upper == null) return false
    val cmp = compareVersions(interval.lower, interval.upper)
    return cmp > 0 || (cmp == 0 && !(interval.lowerInclusive && interval.upperInclusive))
  }

  private fun parseInterval(interval: String): Interval {
    val bounds = interval.substring(1, interval.length - 1).split(',').map { it.trim().ifEmpty { null } }
    val upper = if (bounds.size == 1) bounds[0] else bounds[1]
    return Interval(bounds[0], interval.first() == '[', upper, interval.last() == ']')
  }

  private fun compareVersions(a: String, b: String): Int =
    versionScheme.parseVersion(a).compareTo(versionScheme.parseVersion(b))

  private fun compareLower(a: Interval, b: Interval): Int = when {
    a.lower == null -> if (b.lower == null) 0 else -1
    b.lower == null -> 1
    else -> compareVersions(a.lower, b.lower).takeIf { it != 0 } ?: compareValues(!a.lowerInclusive, !b.lowerInclusive)
  }

  private fun compareUpper(a: Interval, b: Interval): Int = when {
    a.upper == null -> if (b.upper == null) 0 else 1
    b.upper == null -> -1
    else -> compareVersions(a.upper, b.upper).takeIf { it != 0 } ?: compareValues(a.upperInclusive, b.upperInclusive)
  }

  /** Whether [next], starting at or after [last], overlaps or touches it. */
  private fun overlaps(last: Interval, next: Interval): Boolean {
    if (last.upper == null || next.lower == null) return true
    val cmp = compareVersions(next.lower, last.upper)
    return cmp < 0 || (cmp == 0 && (last.upperInclusive || next.lowerInclusive))
  }
}
