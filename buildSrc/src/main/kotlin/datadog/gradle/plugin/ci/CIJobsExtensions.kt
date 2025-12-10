package datadog.gradle.plugin.ci

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.extra
import kotlin.math.abs

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

    val selectedSlot = parts[0]
    val totalSlots = parts[1]

    // Distribution numbers when running on rootProject.allprojects indicates
    // bucket sizes are reasonably balanced:
    //
    // * size  4 distribution: {2=146, 0=143, 1=157, 3=145}
    // * size  6 distribution: {4=100, 0=92, 3=97, 2=97, 1=108, 5=97}
    // * size  8 distribution: {2=62, 4=72, 0=71, 5=70, 7=78, 6=84, 1=87, 3=67}
    // * size 10 distribution: {8=62, 0=65, 5=70, 9=59, 3=54, 1=56, 6=63, 4=47, 2=52, 7=63}
    // * size 12 distribution: {10=55, 0=47, 4=45, 9=46, 8=51, 3=51, 2=46, 1=59, 5=52, 7=49, 11=45, 6=45}
    val currentTaskPartition = abs(project.path.hashCode() % totalSlots.toInt())

    project.logger.info(
      "Project {} assigned to slot {}/{}, active slot is {}",
      project.path,
      currentTaskPartition,
      totalSlots,
      selectedSlot,
    )

    currentTaskPartition == selectedSlot.toInt()
  }.orElse(true)

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
        isInSelectedSlot.get() &&
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

