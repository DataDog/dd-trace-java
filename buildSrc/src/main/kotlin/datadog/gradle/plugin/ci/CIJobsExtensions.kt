package datadog.gradle.plugin.ci

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.extra
import kotlin.math.abs

private fun selectedSlotFor(
  rootProject: Project,
  identityPath: String,
  kind: String,
): Provider<Boolean> =
  rootProject.providers.gradleProperty("slot").map { slot ->
    val parts = slot.split("/")
    if (parts.size != 2) {
      rootProject.logger.warn("Invalid slot format '{}', expected 'X/Y'. Treating all {}s as selected.", slot, kind)
      return@map true
    }

    val selectedSlot = parts[0].toIntOrNull()
    val totalSlots = parts[1].toIntOrNull()

    if (selectedSlot == null || totalSlots == null || totalSlots <= 0) {
      rootProject.logger.warn(
        "Invalid slot values '{}', expected numeric 'X/Y' with Y > 0. Treating all {}s as selected.",
        slot,
        kind,
      )
      return@map true
    }

    val slotForIdentity = abs(identityPath.hashCode() % totalSlots) + 1 // Convert to 1-based
    slotForIdentity == selectedSlot
  }.orElse(true)

/**
 * Determines if the current project is in the selected slot.
 *
 * The "slot" property should be provided in the format "X/Y", where X is the selected slot (1-based)
 * and Y is the total number of slots.
 *
 * If the "slot" property is not provided, all projects are considered to be in the selected slot.
 */
val Project.isInSelectedSlot: Provider<Boolean>
  get() = selectedSlotFor(rootProject, path, "project")

val Task.isInSelectedSlot: Provider<Boolean>
  get() = selectedSlotFor(project.rootProject, path, "task")

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
              dependsOn(aggregateTestTasksFor(subproject, subProjTaskName).filter { testTask ->
                testTask.isInSelectedSlot.get()
              })
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
