package datadog.gradle.plugin.ci

import datadog.gradle.plugin.testJvmConstraints.CHECK_COVERAGE
import datadog.gradle.plugin.testJvmConstraints.CHECK_COVERAGE_ON_FIRST_TEST_JVM
import datadog.gradle.plugin.testJvmConstraints.checksCoverage
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.extra
import java.io.File
import kotlin.math.abs

/** Marker for every task of a project. */
internal const val ALL_TASKS = "all"

/** Changed files grouped by their owning projects, stored as an extra property on the root project. */
internal const val CHANGED_FILES_BY_PROJECT = "changedFilesByProject"

private const val AFFECTED_TASKS = "affectedTasksByProject"

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
    val projectSlot = abs(project.path.hashCode() % totalSlots) + 1 // Convert to 1-based

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
 * Returns the path of the first affected task found among the given task and its dependencies.
 *
 * @param baseTask the task to start the search from
 * @param affectedTasksByProject the affected task names, by project, where [ALL_TASKS] stands for every task
 * @return the affected task path, or `null` when neither the task nor its dependencies are affected
 */
internal fun findAffectedTaskPath(baseTask: Task, affectedTasksByProject: Map<Project, Set<String>>): String? {
  val visited = mutableSetOf<Task>()
  val queue = mutableListOf(baseTask)

  while (queue.isNotEmpty()) {
    val t = queue.removeAt(0)
    if (visited.contains(t)) {
      continue
    }
    visited.add(t)

    val affectedTasks = affectedTasksByProject[t.project]
    if (affectedTasks != null && (ALL_TASKS in affectedTasks || t.name in affectedTasks)) {
      return "${t.project.path}:${t.name}"
    }

    t.taskDependencies.getDependencies(t).forEach { queue.add(it) }
  }
  return null
}

/**
 * A source directory of a project's source set.
 *
 * @property buildTaskNames the names of the tasks building the source set: its compile, resources and classes tasks
 */
internal data class SourceDirectory<P>(
  val project: P,
  val dir: File,
  val buildTaskNames: Set<String>,
  val isMain: Boolean,
)

/**
 * Maps changed files to the tasks they affect.
 *
 * A changed file affects the tasks building every source set containing it, whichever project declares the source
 * set. A file in a main source set, or in no source set of the project containing it, affects all the tasks of that
 * project.
 *
 * @param changedFilesByProject the changed files, by the project whose directory contains them
 * @param sourceDirectories the source directories of every project
 * @return the affected task names, by project, where [ALL_TASKS] stands for every task
 */
internal fun <P> affectedTasks(
  changedFilesByProject: Map<P, List<File>>,
  sourceDirectories: List<SourceDirectory<P>>,
): Map<P, Set<String>> {
  val sourceDirectoriesByDir = sourceDirectories.groupBy { it.dir }
  val affected = mutableMapOf<P, MutableSet<String>>()
  for ((owner, files) in changedFilesByProject) {
    for (file in files) {
      val containing = generateSequence(file.parentFile) { it.parentFile }
        .flatMap { sourceDirectoriesByDir[it].orEmpty() }
        .toList()
      for (sourceDirectory in containing) {
        val tasks = if (sourceDirectory.isMain) setOf(ALL_TASKS) else sourceDirectory.buildTaskNames
        affected.getOrPut(sourceDirectory.project) { mutableSetOf() }.addAll(tasks)
      }
      if (containing.none { it.project == owner }) {
        affected.getOrPut(owner) { mutableSetOf() }.add(ALL_TASKS)
      }
    }
  }
  return affected
}

/**
 * Returns the tasks affected by the git changes, by project.
 *
 * Computed once, on first use, as it reads the source sets of every project, which are only known once they are all
 * configured.
 */
private fun Project.affectedTasksByProject(): Map<Project, Set<String>> {
  val extra = rootProject.extra
  if (!extra.has(AFFECTED_TASKS)) {
    @Suppress("UNCHECKED_CAST")
    val changedFilesByProject = extra.get(CHANGED_FILES_BY_PROJECT) as Map<Project, List<File>>
    val sourceDirectories = rootProject.subprojects.flatMap { subproject ->
      val sourceSets = subproject.extensions.findByType(SourceSetContainer::class.java) ?: return@flatMap emptyList()
      sourceSets.flatMap { sourceSet ->
        val isMain = SourceSet.isMain(sourceSet) || sourceSet.name.startsWith("${SourceSet.MAIN_SOURCE_SET_NAME}_")
        // Some consumers depend on a compile task output rather than on the source set output
        val buildTaskNames = setOf(
          sourceSet.classesTaskName,
          sourceSet.processResourcesTaskName,
          sourceSet.compileJavaTaskName,
          sourceSet.getCompileTaskName("groovy"),
          sourceSet.getCompileTaskName("scala"),
          sourceSet.getCompileTaskName("kotlin"),
        )
        sourceSet.allSource.srcDirs.map { SourceDirectory(subproject, it, buildTaskNames, isMain) }
      }
    }
    val affected = affectedTasks(changedFilesByProject, sourceDirectories)
    affected.forEach { (project, tasks) -> logger.warn("Affected tasks in ${project.path}: ${tasks.sorted()}") }
    extra.set(AFFECTED_TASKS, affected)
  }
  @Suppress("UNCHECKED_CAST")
  return extra.get(AFFECTED_TASKS) as Map<Project, Set<String>>
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
  val checkCoverage = forceCoverage || rootProject.providers.gradleProperty(CHECK_COVERAGE).isPresent
  val checkCoverageOnFirstTestJvm = rootProject.providers.gradleProperty(CHECK_COVERAGE_ON_FIRST_TEST_JVM).isPresent
  val testJvm = rootProject.providers.gradleProperty("testJvm").orNull
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
            val affectedTaskPath = findAffectedTaskPath(testTask, project.affectedTasksByProject())
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

        if (isAffected && subproject.checksCoverage(checkCoverage, checkCoverageOnFirstTestJvm, testJvm)) {
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
