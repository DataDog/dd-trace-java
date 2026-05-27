package datadog.gradle.plugin.ci

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.extra

private sealed class SlotSelection {
  object All : SlotSelection()
  data class Active(val index: Int, val total: Int) : SlotSelection()
}

private const val SLOT_CACHE_KEY = "datadog.ci.slotSelection"

/**
 * Parsed -Pslot=X/Y, cached on the root project so we parse + warn at most once per build.
 */
private fun Project.slotSelection(): SlotSelection {
  val root = rootProject
  if (root.extra.has(SLOT_CACHE_KEY)) {
    return root.extra.get(SLOT_CACHE_KEY) as SlotSelection
  }
  val raw = root.providers.gradleProperty("slot").orNull
  val parsed = parseSlot(raw, root)
  root.extra.set(SLOT_CACHE_KEY, parsed)
  if (parsed is SlotSelection.Active) {
    root.logger.lifecycle("CI slot sharding active: {}/{}", parsed.index, parsed.total)
  }
  return parsed
}

private fun parseSlot(raw: String?, root: Project): SlotSelection {
  if (raw.isNullOrBlank()) return SlotSelection.All
  val parts = raw.split("/")
  if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
    root.logger.warn("Invalid -Pslot='{}', expected 'X/Y'. Disabling slot sharding.", raw)
    return SlotSelection.All
  }
  val index = parts[0].toIntOrNull()
  val total = parts[1].toIntOrNull()
  if (index == null || total == null || total <= 0 || index < 1 || index > total) {
    root.logger.warn("Invalid -Pslot='{}', expected 'X/Y' with 1 <= X <= Y. Disabling slot sharding.", raw)
    return SlotSelection.All
  }
  return SlotSelection.Active(index, total)
}

/**
 * Murmur3 32-bit finalizer. Avalanches bits so similar inputs (paths sharing a long common
 * prefix like `:dd-java-agent:instrumentation:...`) land in different slots.
 */
private fun avalanche(hash: Int): Int {
  var h = hash
  h = h xor (h ushr 16)
  h *= 0x85ebca6b.toInt()
  h = h xor (h ushr 13)
  h *= 0xc2b2ae35.toInt()
  h = h xor (h ushr 16)
  return h
}

/** 1-based slot index this identityPath hashes to, given a total of `total` slots. */
private fun slotOf(identityPath: String, total: Int): Int =
  Math.floorMod(avalanche(identityPath.hashCode()), total) + 1

private fun selectedSlotFor(project: Project, identityPath: String): Boolean =
  when (val s = project.slotSelection()) {
    SlotSelection.All -> true
    is SlotSelection.Active -> slotOf(identityPath, s.total) == s.index
  }

/**
 * Whether this project (or task) belongs in the currently selected slot.
 *
 * The "slot" property should be provided as "X/Y" where X is the 1-based selected slot and Y is
 * the total number of slots. If unset, everything is in-slot.
 */
val Project.isInSelectedSlot: Provider<Boolean>
  get() = providers.provider { selectedSlotFor(this, path) }

val Task.isInSelectedSlot: Provider<Boolean>
  get() = project.providers.provider { selectedSlotFor(project, path) }

private fun Project.aggregateTestTasksFor(subproject: Project, aggregateTaskName: String): List<Test> =
  when (aggregateTaskName) {
    "allTests" -> subproject.tasks.withType(Test::class.java).matching { testTask ->
      !testTask.name.contains("latest", ignoreCase = true) && testTask.name != "traceAgentTest"
    }.toList()
    "allLatestDepTests" -> subproject.tasks.withType(Test::class.java).matching { testTask ->
      testTask.name.contains("latest", ignoreCase = true)
    }.toList()
    else -> emptyList()
  }

/**
 * Returns the task's path, given affected projects, if this task or its dependencies are affected by git changes.
 */
internal fun findAffectedTaskPath(baseTask: Task, affectedProjects: Map<Project, Set<String>>): String? {
  val visited = mutableSetOf<Task>()
  val queue = mutableListOf(baseTask)

  while (queue.isNotEmpty()) {
    val t = queue.removeAt(0)
    if (visited.contains(t)) {
      continue
    }
    visited.add(t)

    val affectedTasks = affectedProjects[t.project]
    if (affectedTasks != null) {
      if (affectedTasks.contains("all")) {
        return "${t.project.path}:${t.name}"
      }
      if (affectedTasks.contains(t.name)) {
        return "${t.project.path}:${t.name}"
      }
    }

    t.taskDependencies.getDependencies(t).forEach { queue.add(it) }
  }
  return null
}

/**
 * Creates a single aggregate root task that depends on matching subproject tasks
 */
private fun Project.createRootTask(
  rootTaskName: String,
  subProjTaskName: String,
  includePrefixes: List<String>,
  excludePrefixes: List<String>,
  forceCoverage: Boolean
) {
  val coverage = forceCoverage || rootProject.providers.gradleProperty("checkCoverage").isPresent
  val taskLevelSlotting = !coverage && (subProjTaskName == "allTests" || subProjTaskName == "allLatestDepTests")
  tasks.register(rootTaskName) {
    var consideredTestTasks = 0
    var selectedTestTasks = 0
    subprojects.forEach { subproject ->
      if (
        includePrefixes.any { subproject.path.startsWith(it) } &&
        !excludePrefixes.any { subproject.path.startsWith(it) }
      ) {
        if (!taskLevelSlotting && !subproject.isInSelectedSlot.get()) {
          return@forEach
        }

        val aggregateTask = subproject.tasks.findByName(subProjTaskName)
        var isAffected = true

        if (aggregateTask != null) {
          val useGitChanges = rootProject.extra.get("useGitChanges") as Boolean
          if (useGitChanges) {
            @Suppress("UNCHECKED_CAST")
            val affectedProjects = rootProject.extra.get("affectedProjects") as Map<Project, Set<String>>
            val affectedTaskPath = findAffectedTaskPath(aggregateTask, affectedProjects)
            if (affectedTaskPath != null) {
              logger.warn("Selecting ${subproject.path}:$subProjTaskName (affected by $affectedTaskPath)")
            } else {
              logger.warn("Skipping ${subproject.path}:$subProjTaskName (not affected by changed files)")
              isAffected = false
            }
          }
          if (isAffected) {
            if (taskLevelSlotting) {
              val candidates = aggregateTestTasksFor(subproject, subProjTaskName)
              val selected = candidates.filter { it.isInSelectedSlot.get() }
              consideredTestTasks += candidates.size
              selectedTestTasks += selected.size
              dependsOn(selected)
            } else {
              dependsOn(aggregateTask)
            }
          }
        }

        if (isAffected && coverage) {
          val coverageTask = subproject.tasks.findByName("jacocoTestReport")
          if (coverageTask != null) {
            dependsOn(coverageTask)
          }
          val verificationTask = subproject.tasks.findByName("jacocoTestCoverageVerification")
          if (verificationTask != null) {
            dependsOn(verificationTask)
          }
        }
      }
    }
    if (taskLevelSlotting && consideredTestTasks > 0) {
      logger.lifecycle(
        "$rootTaskName: slot selected $selectedTestTasks of $consideredTestTasks Test tasks ($subProjTaskName)"
      )
    }
  }
}

/**
 * Creates aggregate test tasks for CI using createRootTask() above
 *
 * Creates three subtasks for the given base task name:
 * - ${baseTaskName}Test - runs allTests
 * - ${baseTaskName}LatestDepTest - runs allLatestDepTests
 * - ${baseTaskName}Check - runs check
 */
fun Project.testAggregate(
  baseTaskName: String,
  includePrefixes: List<String>,
  excludePrefixes: List<String> = emptyList(),
  forceCoverage: Boolean = false
) {
  createRootTask("${baseTaskName}Test", "allTests", includePrefixes, excludePrefixes, forceCoverage)
  createRootTask("${baseTaskName}LatestDepTest", "allLatestDepTests", includePrefixes, excludePrefixes, forceCoverage)
  createRootTask("${baseTaskName}Check", "check", includePrefixes, excludePrefixes, forceCoverage)
}
