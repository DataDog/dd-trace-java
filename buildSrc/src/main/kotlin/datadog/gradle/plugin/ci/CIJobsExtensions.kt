package datadog.gradle.plugin.ci

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.extra
import kotlin.math.abs

private const val instrumentationProjectPrefix = ":dd-java-agent:instrumentation:"
private const val defaultInstrumentationTestDurationSeconds = 30

// Approximate project durations from CI Visibility pipeline 142652739. Keeping the data here makes
// the assignment reproducible; projects not listed here use the conservative default above.
private val instrumentationTestDurationSeconds = mapOf(
  ":dd-java-agent:instrumentation:apache-httpclient:apache-httpclient-4.0" to 205,
  ":dd-java-agent:instrumentation:armeria:armeria-grpc-0.84" to 720,
  ":dd-java-agent:instrumentation:aws-java:aws-java-eventbridge-2.0" to 89,
  ":dd-java-agent:instrumentation:aws-java:aws-java-s3-2.0" to 105,
  ":dd-java-agent:instrumentation:aws-java:aws-java-sfn-2.0" to 94,
  ":dd-java-agent:instrumentation:aws-java:aws-java-sns-1.0" to 75,
  ":dd-java-agent:instrumentation:aws-java:aws-java-sns-2.0" to 144,
  ":dd-java-agent:instrumentation:couchbase:couchbase-2.0" to 87,
  ":dd-java-agent:instrumentation:couchbase:couchbase-3.1" to 157,
  ":dd-java-agent:instrumentation:couchbase:couchbase-3.2" to 157,
  ":dd-java-agent:instrumentation:datastax-cassandra:datastax-cassandra-4.0" to 188,
  ":dd-java-agent:instrumentation:java:java-concurrent:java-concurrent-1.8" to 189,
  ":dd-java-agent:instrumentation:jdbc" to 697,
  ":dd-java-agent:instrumentation:lettuce:lettuce-5.0" to 400,
  ":dd-java-agent:instrumentation:liberty:liberty-20.0" to 100,
  ":dd-java-agent:instrumentation:liberty:liberty-23.0" to 87,
  ":dd-java-agent:instrumentation:maven:maven-3.2.1" to 146,
  ":dd-java-agent:instrumentation:rabbitmq-amqp-2.7" to 185,
  ":dd-java-agent:instrumentation:servlet:javax-servlet:javax-servlet-3.0" to 238,
  ":dd-java-agent:instrumentation:spring:spring-webflux:spring-webflux-5.0" to 500,
  ":dd-java-agent:instrumentation:spring:spring-webmvc:spring-webmvc-3.1" to 130,
)

private fun Project.durationWeightedInstrumentationTestSlot(totalSlots: Int): Int? {
  if (!path.startsWith(instrumentationProjectPrefix)) {
    return null
  }

  val slotDurations = IntArray(totalSlots)
  val projectsByDescendingDuration = rootProject.subprojects
    .asSequence()
    .filter {
      it.path.startsWith(instrumentationProjectPrefix) &&
        it.tasks.findByName("allTests") != null
    }
    .map { it.path }
    .sortedWith(
      compareByDescending<String> {
        instrumentationTestDurationSeconds[it] ?: defaultInstrumentationTestDurationSeconds
      }.thenBy { it }
    )

  projectsByDescendingDuration.forEach { projectPath ->
    val lightestSlot = slotDurations.indices.minBy { slotDurations[it] }
    if (projectPath == path) {
      return lightestSlot + 1
    }
    slotDurations[lightestSlot] +=
      instrumentationTestDurationSeconds[projectPath] ?: defaultInstrumentationTestDurationSeconds
  }
  return null
}

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

    // Distribution numbers when running on rootProject.allprojects indicates
    // bucket sizes are reasonably balanced:
    //
    // * size  4 distribution: {2=146, 0=143, 1=157, 3=145}
    // * size  6 distribution: {4=100, 0=92, 3=97, 2=97, 1=108, 5=97}
    // * size  8 distribution: {2=62, 4=72, 0=71, 5=70, 7=78, 6=84, 1=87, 3=67}
    // * size 10 distribution: {8=62, 0=65, 5=70, 9=59, 3=54, 1=56, 6=63, 4=47, 2=52, 7=63}
    // * size 12 distribution: {10=55, 0=47, 4=45, 9=46, 8=51, 3=51, 2=46, 1=59, 5=52, 7=49, 11=45, 6=45}
    val defaultProjectSlot = abs(project.path.hashCode() % totalSlots) + 1 // Convert to 1-based
    val useDurationWeightedInstrumentationTests =
      rootProject.providers.gradleProperty("durationWeightedInstrumentationTests").isPresent && totalSlots == 12
    val projectSlot = if (useDurationWeightedInstrumentationTests) {
      project.durationWeightedInstrumentationTestSlot(totalSlots) ?: defaultProjectSlot
    } else {
      defaultProjectSlot
    }

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
