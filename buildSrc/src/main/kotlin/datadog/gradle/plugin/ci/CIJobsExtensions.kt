package datadog.gradle.plugin.ci

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.extra
import kotlin.math.abs

/**
 * Test tasks that run in another slot than their project, keyed by total slot count.
 *
 * The test tasks of a project run one after another, so a project with many slow test tasks sets
 * the minimum duration of its slot. Moving some of them to a light slot splits that chain in two.
 * Tasks not listed here, or that no longer exist, run in their project's slot.
 * Don't list tasks of projects that collect coverage: their report needs all tests in one slot.
 */
internal val TEST_TASK_SLOT_OVERRIDES: Map<Int, Map<String, Int>> = mapOf(
  // Measured with CI Visibility test durations of full `test_inst` runs (2026-11-06 to 2026-11-10).
  // Each split makes the next longest project the bottleneck, so recheck the slowest shard after test suite changes.
  8 to mapOf(
    // lettuce-5.0 (slot 6) ran ~25 min of tests in a row on JVM 8 and 11
    ":dd-java-agent:instrumentation:lettuce:lettuce-5.0:lettuce60Test" to 3,
    ":dd-java-agent:instrumentation:lettuce:lettuce-5.0:lettuce60ForkedTest" to 3,
    ":dd-java-agent:instrumentation:lettuce:lettuce-5.0:lettuce61Test" to 3,
    ":dd-java-agent:instrumentation:lettuce:lettuce-5.0:lettuce61ForkedTest" to 3,
    ":dd-java-agent:instrumentation:lettuce:lettuce-5.0:lettuce62Test" to 4,
    ":dd-java-agent:instrumentation:lettuce:lettuce-5.0:lettuce62ForkedTest" to 4,
    // jdbc (slot 5) ran ~18 min of tests in a row on JVM 8 and 11
    ":dd-java-agent:instrumentation:jdbc:oldH2Test" to 8,
    ":dd-java-agent:instrumentation:jdbc:oldPostgresTest" to 8,
    // akka-http-10.0 (slot 2) ran ~33 min of tests in a row on JVM 11 once lettuce was split
    ":dd-java-agent:instrumentation:akka:akka-http:akka-http-10.0:version101Test" to 6,
    ":dd-java-agent:instrumentation:akka:akka-http:akka-http-10.0:version101ForkedTest" to 6,
    ":dd-java-agent:instrumentation:akka:akka-http:akka-http-10.0:version101IastTest" to 6,
    ":dd-java-agent:instrumentation:akka:akka-http:akka-http-10.0:version102Scala213Test" to 7,
    ":dd-java-agent:instrumentation:akka:akka-http:akka-http-10.0:version102Scala213ForkedTest" to 7,
    ":dd-java-agent:instrumentation:akka:akka-http:akka-http-10.0:version102IastTest" to 7,
  ),
)

/** Slot of a project (1-based). */
internal fun projectSlot(projectPath: String, totalSlots: Int): Int =
  // Distribution numbers when running on rootProject.allprojects indicates
  // bucket sizes are reasonably balanced:
  //
  // * size  4 distribution: {2=146, 0=143, 1=157, 3=145}
  // * size  6 distribution: {4=100, 0=92, 3=97, 2=97, 1=108, 5=97}
  // * size  8 distribution: {2=62, 4=72, 0=71, 5=70, 7=78, 6=84, 1=87, 3=67}
  // * size 10 distribution: {8=62, 0=65, 5=70, 9=59, 3=54, 1=56, 6=63, 4=47, 2=52, 7=63}
  // * size 12 distribution: {10=55, 0=47, 4=45, 9=46, 8=51, 3=51, 2=46, 1=59, 5=52, 7=49, 11=45, 6=45}
  abs(projectPath.hashCode() % totalSlots) + 1

/** Slot of a test task: its override, or else its project's slot. */
internal fun testTaskSlot(taskPath: String, totalSlots: Int): Int =
  TEST_TASK_SLOT_OVERRIDES[totalSlots]?.get(taskPath)
    ?: projectSlot(taskPath.substringBeforeLast(':'), totalSlots)

/** Slots that run at least one test task of a project. */
internal fun projectSlots(projectPath: String, totalSlots: Int): Set<Int> =
  setOf(projectSlot(projectPath, totalSlots)) +
    TEST_TASK_SLOT_OVERRIDES[totalSlots].orEmpty().filterKeys { it.substringBeforeLast(':') == projectPath }.values

/**
 * Parses the "slot" property into the selected slot and the total number of slots, or returns null
 * when all projects are selected.
 *
 * The "slot" property should be provided in the format "X/Y", where X is the selected slot (1-based)
 * and Y is the total number of slots.
 */
private fun Project.parseSlot(slot: String): Pair<Int, Int>? {
  val parts = slot.split("/")
  if (parts.size != 2) {
    project.logger.warn("Invalid slot format '{}', expected 'X/Y'. Treating all projects as selected.", slot)
    return null
  }

  // When CI_NODE_INDEX or CI_NODE_TOTAL is unset in non-parallel jobs, one part may be empty (e.g. slot="/1") — treat as no filtering
  if (parts[0].isBlank() || parts[1].isBlank()) {
    project.logger.info("Incomplete slot value '{}', CI_NODE_INDEX or CI_NODE_TOTAL not set. Treating all projects as selected.", slot)
    return null
  }

  val selectedSlot = parts[0].toIntOrNull()
  val totalSlots = parts[1].toIntOrNull()

  if (selectedSlot == null || totalSlots == null || totalSlots <= 0) {
    project.logger.warn("Invalid slot values '{}', expected numeric 'X/Y' with Y > 0. Treating all projects as selected.", slot)
    return null
  }
  return selectedSlot to totalSlots
}

/**
 * Determines if the current project is in the selected slot, i.e. if the slot runs any of its test tasks.
 *
 * If the "slot" property is not provided, all projects are considered to be in the selected slot.
 */
val Project.isInSelectedSlot: Provider<Boolean>
  get() = rootProject.providers.gradleProperty("slot").map { slot ->
    val (selectedSlot, totalSlots) = parseSlot(slot) ?: return@map true

    val projectSlots = projectSlots(project.path, totalSlots)

    project.logger.info(
      "Project {} assigned to slots {}/{}, active slot is {}",
      project.path,
      projectSlots,
      totalSlots,
      selectedSlot,
    )

    selectedSlot in projectSlots
  }.orElse(true)

/**
 * Determines if the selected slot is the current project's own slot, ignoring test task overrides.
 *
 * If the "slot" property is not provided, all projects are considered to be in the selected slot.
 */
val Project.isInOwnSlot: Provider<Boolean>
  get() = rootProject.providers.gradleProperty("slot").map { slot ->
    val (selectedSlot, totalSlots) = parseSlot(slot) ?: return@map true
    projectSlot(project.path, totalSlots) == selectedSlot
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
  val slot = rootProject.providers.gradleProperty("slot").orNull?.let { parseSlot(it) }
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
            if (slot == null) {
              dependsOn(testTask)
            } else {
              // Run only the test tasks of the selected slot, as a project's tasks can be split across slots
              val (selectedSlot, totalSlots) = slot
              dependsOn(testTask.taskDependencies.getDependencies(testTask).filter { testTaskSlot(it.path, totalSlots) == selectedSlot })
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
