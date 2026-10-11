package datadog.gradle.plugin.ci

import datadog.gradle.plugin.ci.TestShardBalancer.DEFAULT_WEIGHT_SECONDS
import datadog.gradle.plugin.ci.TestShardBalancer.assignSlots
import datadog.gradle.plugin.ci.TestShardBalancer.loadDefaultWeights
import datadog.gradle.plugin.ci.TestShardBalancer.loadWeights
import datadog.gradle.plugin.ci.TestShardBalancer.weightOf
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.TreeSet
import kotlin.random.Random

class TestShardBalancerTest {

  @ParameterizedTest(name = "{0} slots")
  @ValueSource(ints = [1, 2, 4, 6, 8, 12])
  fun `every project is selected by exactly one slot`(totalSlots: Int) {
    val assignment = assignSlots(PROJECT_PATHS, totalSlots, WEIGHTS)

    val selectedBySlot = (1..totalSlots).map { slot -> PROJECT_PATHS.filter { assignment[it] == slot } }
    assertThat(selectedBySlot.flatten()).containsExactlyInAnyOrderElementsOf(PROJECT_PATHS)
  }

  @ParameterizedTest(name = "{0} slots")
  @ValueSource(ints = [1, 2, 4, 6, 8, 12])
  fun `assigned slots are within range`(totalSlots: Int) {
    val assignment = assignSlots(PROJECT_PATHS, totalSlots, WEIGHTS)

    assertThat(assignment.values).allSatisfy { slot -> assertThat(slot).isBetween(1, totalSlots) }
  }

  @Test
  fun `assignment does not depend on input order`() {
    val expected = assignSlots(PROJECT_PATHS, 8, WEIGHTS)

    assertThat(assignSlots(PROJECT_PATHS, 8, WEIGHTS)).isEqualTo(expected)
    for (seed in 1..5) {
      assertThat(assignSlots(PROJECT_PATHS.shuffled(Random(seed)), 8, WEIGHTS)).isEqualTo(expected)
    }
  }

  @Test
  fun `instrumentation shards are balanced by duration`() {
    val instrumentationPaths = PROJECT_PATHS.filter { it.startsWith(INSTRUMENTATION_PREFIX) }
    assertThat(instrumentationPaths).hasSizeGreaterThan(300)

    val assignment = assignSlots(PROJECT_PATHS, 8, WEIGHTS)

    val loads = DoubleArray(8)
    instrumentationPaths.forEach { loads[assignment.getValue(it) - 1] += weightOf(it, WEIGHTS) }
    assertThat(loads.min()).isPositive()
    assertThat(loads.max() / loads.min()).isLessThanOrEqualTo(1.25)
  }

  @Test
  fun `heaviest projects land in different slots`() {
    val assignment = assignSlots(PROJECT_PATHS, 8, WEIGHTS)

    assertThat(assignment[JDBC]).isNotEqualTo(assignment[AKKA_HTTP_10_0])
    val heaviest = WEIGHTS.entries.sortedByDescending { it.value }.take(8).map { it.key }
    assertThat(heaviest.map { assignment[it] }).doesNotHaveDuplicates()
  }

  @Test
  fun `unknown projects receive the default weight`() {
    assertThat(weightOf(":unknown", mapOf(":known" to 1.5))).isEqualTo(DEFAULT_WEIGHT_SECONDS)
    assertThat(weightOf(":known", mapOf(":known" to 1.5))).isEqualTo(1.5)

    // A known project worth two default weights is balanced by two unknown projects
    val assignment = assignSlots(
      listOf(":unknown-a", ":known", ":unknown-b"),
      2,
      mapOf(":known" to 2 * DEFAULT_WEIGHT_SECONDS)
    )
    assertThat(assignment).isEqualTo(mapOf(":known" to 1, ":unknown-a" to 2, ":unknown-b" to 2))
  }

  @Test
  fun `single slot selects every project`() {
    val assignment = assignSlots(PROJECT_PATHS, 1, WEIGHTS)

    assertThat(assignment).hasSize(PROJECT_PATHS.size)
    assertThat(assignment.values).containsOnly(1)
  }

  @Test
  fun `empty project list yields an empty assignment`() {
    assertThat(assignSlots(emptyList(), 8, WEIGHTS)).isEmpty()
  }

  @Test
  fun `non positive slot count is rejected`() {
    assertThatThrownBy { assignSlots(PROJECT_PATHS, 0, WEIGHTS) }
      .isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun `bundled weights file is parsed`() {
    assertThat(WEIGHTS).hasSize(24).containsKeys(JDBC, AKKA_HTTP_10_0)
    assertThat(WEIGHTS.values).allSatisfy { weight -> assertThat(weight).isPositive() }
  }

  @Test
  fun `only the weights object is read`() {
    val weights = loadWeights("""{"_comment": ["c"], "_metadata": {"projects_measured": 1}, "weights": {":a": 1.5}}""")

    assertThat(weights).isEqualTo(mapOf(":a" to 1.5))
  }

  @Test
  fun `invalid weights are rejected`() {
    assertThatThrownBy { loadWeights("""{"_comment": []}""") }
      .isInstanceOf(IllegalArgumentException::class.java)
    assertThatThrownBy { loadWeights("""{"weights": {":a": -1}}""") }
      .isInstanceOf(IllegalArgumentException::class.java)
    assertThatThrownBy { loadWeights("""{"weights": {":a": "slow"}}""") }
      .isInstanceOf(IllegalArgumentException::class.java)
  }

  companion object {
    private const val INSTRUMENTATION_PREFIX = ":dd-java-agent:instrumentation:"
    private const val JDBC = ":dd-java-agent:instrumentation:jdbc"
    private const val AKKA_HTTP_10_0 = ":dd-java-agent:instrumentation:akka:akka-http:akka-http-10.0"

    private val WEIGHTS = loadDefaultWeights()

    /** Project paths included by the repository settings, plus the implicit parent projects Gradle creates. */
    private val PROJECT_PATHS: List<String> = run {
      val settings = File(System.getProperty("user.dir")).parentFile.resolve("settings.gradle.kts")
      val includePattern = Regex("\"(:[^\"]+)\"")
      val paths = TreeSet(listOf(":"))
      settings.readLines()
        .filterNot { it.trimStart().startsWith("//") }
        .flatMap { line -> includePattern.findAll(line).map { it.groupValues[1] }.toList() }
        .forEach { include ->
          val segments = include.removePrefix(":").split(":")
          segments.indices.forEach { i -> paths.add(":" + segments.subList(0, i + 1).joinToString(":")) }
        }
      paths.toList()
    }
  }
}
