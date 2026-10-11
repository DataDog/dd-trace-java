package datadog.gradle.plugin.ci

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.extra

/**
 * Determines if the current project is in the selected slot.
 *
 * The "slot" property should be provided in the format "X/Y", where X is the selected slot (1-based)
 * and Y is the total number of slots.
 *
 * If the "slot" property is not provided, all projects are considered to be in the selected slot.
 */
val Project.isInSelectedSlot: Provider<Boolean>
  get() = rootProject.providers.gradleProperty("slot").map { slot ->
    val parts = slot.split("/")
    if (parts.size != 2) {
      project.logger.warn("Invalid slot format '{}', expected 'X/Y'. Treating all projects as selected.", slot)
      return@map true
    }

    // When CI_NODE_INDEX or CI_NODE_TOTAL is unset in non-parallel jobs, one part may be empty (e.g. slot="/1") — treat as no filtering
    if (parts[0].isBlank() || parts[1].isBlank()) {
      project.logger.info("Incomplete slot value '{}', CI_NODE_INDEX or CI_NODE_TOTAL not set. Treating all projects as selected.", slot)
      return@map true
    }

    val selectedSlot = parts[0].toIntOrNull()
    val totalSlots = parts[1].toIntOrNull()

    if (selectedSlot == null || totalSlots == null || totalSlots <= 0) {
      project.logger.warn("Invalid slot values '{}', expected numeric 'X/Y' with Y > 0. Treating all projects as selected.", slot)
      return@map true
    }

    // Slots are balanced by measured test duration rather than project count, see
    // TestShardBalancer and buildSrc/src/main/resources/datadog/gradle/plugin/ci/test-shard-weights.json.
    val projectSlot = testShardAssignment(totalSlots).getValue(project.path)

    project.logger.info(
      "Project {} assigned to slot {}/{}, active slot is {}",
      project.path,
      projectSlot,
      totalSlots,
      selectedSlot,
    )

    projectSlot == selectedSlot
  }.orElse(true)

/**
 * Returns the duration-balanced slot assignment of every project for the given number of slots.
 *
 * The assignment is computed once per slot count from all project paths, independently of the
 * selected slot so that every shard agrees on it, and cached on the root project.
 */
private fun Project.testShardAssignment(totalSlots: Int): Map<String, Int> {
  val extra = rootProject.extra
  val key = "testShardAssignment.$totalSlots"
  synchronized(extra) {
    if (!extra.has(key)) {
      val projectPaths = rootProject.allprojects.map { it.path }.sorted()
      extra.set(key, TestShardBalancer.assignSlots(projectPaths, totalSlots, TestShardBalancer.loadDefaultWeights()))
    }
    @Suppress("UNCHECKED_CAST")
    return extra.get(key) as Map<String, Int>
  }
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
  tasks.register(rootTaskName) {
    subprojects.forEach { subproject ->
      if (
        subproject.isInSelectedSlot.get() &&
        includePrefixes.any { subproject.path.startsWith(it) } &&
        !excludePrefixes.any { subproject.path.startsWith(it) }
      ) {
        val testTask = subproject.tasks.findByName(subProjTaskName)
        var isAffected = true

        if (testTask != null) {
          val useGitChanges = rootProject.extra.get("useGitChanges") as Boolean
          if (useGitChanges) {
            @Suppress("UNCHECKED_CAST")
            val affectedProjects = rootProject.extra.get("affectedProjects") as Map<Project, Set<String>>
            val affectedTaskPath = findAffectedTaskPath(testTask, affectedProjects)
            if (affectedTaskPath != null) {
              logger.warn("Selecting ${subproject.path}:$subProjTaskName (affected by $affectedTaskPath)")
            } else {
              logger.warn("Skipping ${subproject.path}:$subProjTaskName (not affected by changed files)")
              isAffected = false
            }
          }
          if (isAffected) {
            dependsOn(testTask)
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
