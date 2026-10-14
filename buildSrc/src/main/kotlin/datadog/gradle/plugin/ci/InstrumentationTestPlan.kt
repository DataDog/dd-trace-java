package datadog.gradle.plugin.ci

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.Serializable
import kotlin.math.abs
import kotlin.math.ceil

/** A CI slot. Missing or malformed values leave instrumentation tests unsharded. */
data class TestSlot(val index: Int, val total: Int) {
  companion object {
    fun parse(value: String?): TestSlot? {
      val parts = value?.split('/') ?: return null
      if (parts.size != 2) return null
      val index = parts[0].toIntOrNull() ?: return null
      val total = parts[1].toIntOrNull() ?: return null
      return if (total > 0 && index in 1..total) TestSlot(index, total) else null
    }
  }
}

/** Class families stay together so nested and anonymous classes follow their enclosing test. */
internal fun testClassFamily(className: String): String = className.substringBefore('$')

/** A split task's selection also gives every unmeasured class exactly one owner. */
data class TestClassSelection(
  val classSlots: Map<String, Int>,
  val fallbackSlot: Int
) : Serializable {
  fun slotFor(className: String): Int = classSlots[testClassFamily(className)] ?: fallbackSlot

  fun fingerprint(slot: Int): String = "$slot:$fallbackSlot:" + classSlots.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }
}

/** Duration-based scheduling of main instrumentation tests, independent of Gradle and git filtering. */
class InstrumentationTestPlan private constructor(
  val projectSlots: Map<String, Int>,
  private val taskSlots: Map<String, Set<Int>>,
  private val classSelections: Map<String, TestClassSelection>,
  val slotWeights: List<Double>
) {
  fun slotsForTask(taskPath: String): Set<Int> = taskSlots[taskPath] ?: setOf(
    projectSlots[taskPath.substringBeforeLast(':')]
      ?: (abs(taskPath.substringBeforeLast(':').hashCode() % slotWeights.size) + 1)
  )

  fun selectsTask(taskPath: String, slot: Int): Boolean = slot in slotsForTask(taskPath)

  fun selectsProject(projectPath: String, slot: Int): Boolean = projectSlots[projectPath] == slot || taskSlots.any { (path, slots) ->
    path.substringBeforeLast(':') == projectPath && slot in slots
  }

  fun classSelection(taskPath: String): TestClassSelection? = classSelections[taskPath]

  companion object {
    const val INSTRUMENTATION_PREFIX = ":dd-java-agent:instrumentation:"
    const val DURATIONS_RESOURCE = "/datadog/gradle/plugin/ci/instrumentation-test-durations.json"
    const val DEFAULT_PROJECT_SECONDS = 20.0
    const val TARGET_UNIT_SECONDS = 120.0
    const val MAX_SLOTS_PER_PROJECT = 4

    fun loadDurations(json: String): Map<String, Map<String, Double>> {
      val tasks = ObjectMapper().readTree(json).path("tasks")
      require(tasks.isObject) { "Instrumentation durations must contain a tasks object" }
      return tasks.properties().associate { (path, classes) ->
        require(
          path.substringBeforeLast(':').startsWith(INSTRUMENTATION_PREFIX) &&
            path.substringAfterLast(':').isNotBlank() && !path.substringAfterLast(':').contains("latest", ignoreCase = true) &&
            path.substringAfterLast(':') != "traceAgentTest" && classes.isObject
        ) {
          "Invalid instrumentation task: $path"
        }
        path to classes.properties().associate { (className, duration) ->
          require(className.isNotBlank() && duration.isNumber && duration.asDouble().isFinite() && duration.asDouble() >= 0) {
            "Invalid duration for $path:$className"
          }
          className to duration.asDouble()
        }
      }
    }

    fun loadDefaultDurations(): Map<String, Map<String, Double>> = checkNotNull(InstrumentationTestPlan::class.java.getResourceAsStream(DURATIONS_RESOURCE)) {
      "Missing $DURATIONS_RESOURCE"
    }.bufferedReader().use { loadDurations(it.readText()) }

    fun create(
      projectPaths: Collection<String>,
      durations: Map<String, Map<String, Double>>,
      totalSlots: Int,
      targetSeconds: Double = TARGET_UNIT_SECONDS,
      maxSlotsPerProject: Int = MAX_SLOTS_PER_PROJECT
    ): InstrumentationTestPlan {
      require(totalSlots > 0 && targetSeconds.isFinite() && targetSeconds > 0 && maxSlotsPerProject > 0)
      require(projectPaths.all { it.startsWith(INSTRUMENTATION_PREFIX) })
      val slotLimit = minOf(totalSlots, maxSlotsPerProject)
      val units = mutableListOf<Work>()
      val splitTasks = mutableMapOf<String, List<Work>>()
      val tasksByProject = durations.toSortedMap().mapValues { it.value.toSortedMap() }.entries.groupBy { it.key.substringBeforeLast(':') }

      projectPaths.distinct().sorted().forEach { project ->
        val tasks = tasksByProject[project].orEmpty().sortedBy { it.key }
        val seconds = tasks.sumOf { it.value.values.sum() }
        if (seconds <= targetSeconds || slotLimit == 1) {
          units.add(Work(project, project, seconds.takeIf { it > 0 } ?: DEFAULT_PROJECT_SECONDS))
        } else {
          // Unmeasured/new tasks retain a project owner rather than disappearing from CI.
          units.add(Work(project, project, DEFAULT_PROJECT_SECONDS))
          tasks.forEach { (task, classes) ->
            val families = classes.entries.groupBy { testClassFamily(it.key) }
              .mapValues { (_, values) -> values.sumOf { it.value } }
            val count = minOf(slotLimit, families.size.coerceAtLeast(1), ceil(families.values.sum() / targetSeconds).toInt().coerceAtLeast(1))
            val groups = List(count) { mutableMapOf<String, Double>() }
            val weights = DoubleArray(count)
            families.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).forEach { (family, duration) ->
              val group = weights.indices.minBy { weights[it] }
              groups[group][family] = duration
              weights[group] += duration
            }
            val work = groups.mapIndexed { index, group ->
              Work("$task#$index", project, weights[index], group.keys)
            }
            units.addAll(work)
            splitTasks[task] = work
          }
        }
      }

      val loads = DoubleArray(totalSlots)
      val owners = mutableMapOf<String, Int>()
      val projectOwners = mutableMapOf<String, MutableSet<Int>>()
      units.sortedWith(compareByDescending<Work> { it.seconds }.thenBy { it.id }).forEach { work ->
        val used = projectOwners.getOrPut(work.project) { mutableSetOf() }
        val eligible = loads.indices.filter { used.size < slotLimit || it + 1 in used }
        val index = eligible.minWith(compareBy<Int> { loads[it] }.thenBy { it })
        owners[work.id] = index + 1
        used.add(index + 1)
        loads[index] += work.seconds
      }
      val projectSlots = projectPaths.distinct().associateWith { owners.getValue(it) }
      val taskSlots = splitTasks.mapValues { (_, groups) -> groups.map { owners.getValue(it.id) }.toSet() }
      val selections = splitTasks.filterValues { it.size > 1 }.mapValues { (_, groups) ->
        val classSlots = groups.flatMap { group -> group.classes.map { it to owners.getValue(group.id) } }.toMap()
        val fallback = groups.minWith(compareBy<Work> { it.seconds }.thenBy { it.id })
        TestClassSelection(classSlots, owners.getValue(fallback.id))
      }
      return InstrumentationTestPlan(projectSlots, taskSlots, selections, loads.toList())
    }

    private data class Work(
      val id: String,
      val project: String,
      val seconds: Double,
      val classes: Set<String> = emptySet()
    )
  }
}
