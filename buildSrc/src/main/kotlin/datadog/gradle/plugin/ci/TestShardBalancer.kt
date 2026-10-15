package datadog.gradle.plugin.ci

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Assigns Gradle projects to CI test shards so that the shards have similar total test duration.
 *
 * Kept free of Gradle types so the assignment can be unit tested in isolation.
 */
internal object TestShardBalancer {
  /** Classpath location of the checked-in per-project test duration weights. */
  const val WEIGHTS_RESOURCE = "/datadog/gradle/plugin/ci/test-shard-weights.json"

  /** Weight, in seconds, of a project that is absent from the weights map. */
  const val DEFAULT_WEIGHT_SECONDS = 20.6

  /** Parses the `weights` object of a weights file; other top-level keys are ignored. */
  fun loadWeights(json: String): Map<String, Double> {
    val weights = ObjectMapper().readTree(json).path("weights")
    require(weights.isObject) { "Test shard weights file must contain a 'weights' object" }
    val result = LinkedHashMap<String, Double>()
    weights.properties().forEach { (path, value) ->
      require(value.isNumber && value.asDouble() >= 0.0) {
        "Test shard weight for '$path' must be a non-negative number, got '$value'"
      }
      result[path] = value.asDouble()
    }
    return result
  }

  /** Loads the weights file bundled with buildSrc. */
  fun loadDefaultWeights(): Map<String, Double> {
    val stream = checkNotNull(TestShardBalancer::class.java.getResourceAsStream(WEIGHTS_RESOURCE)) {
      "Missing test shard weights resource $WEIGHTS_RESOURCE"
    }
    return loadWeights(stream.bufferedReader().use { it.readText() })
  }

  /** Returns the weight of the given project, falling back to [DEFAULT_WEIGHT_SECONDS]. */
  fun weightOf(projectPath: String, weights: Map<String, Double>): Double =
    weights[projectPath] ?: DEFAULT_WEIGHT_SECONDS

  /**
   * Assigns every project path to a 1-based slot using greedy longest-processing-time-first
   * bin-packing: projects are placed heaviest first (ties broken by path) into the currently
   * least loaded slot (ties broken by lowest slot), so the result only depends on the set of
   * paths and the weights, never on input order or hash codes.
   */
  fun assignSlots(projectPaths: List<String>, totalSlots: Int, weights: Map<String, Double>): Map<String, Int> {
    require(totalSlots > 0) { "totalSlots must be positive, got $totalSlots" }
    val ordered = projectPaths.distinct().sortedWith(
      compareByDescending<String> { weightOf(it, weights) }.thenBy { it }
    )
    val loads = DoubleArray(totalSlots)
    val assignment = LinkedHashMap<String, Int>()
    for (path in ordered) {
      var slot = 0
      for (candidate in 1 until totalSlots) {
        if (loads[candidate] < loads[slot]) {
          slot = candidate
        }
      }
      loads[slot] += weightOf(path, weights)
      assignment[path] = slot + 1
    }
    return assignment
  }
}
