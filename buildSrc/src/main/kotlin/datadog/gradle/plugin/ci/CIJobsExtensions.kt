package datadog.gradle.plugin.ci

import datadog.gradle.plugin.testJvmConstraints.CHECK_COVERAGE
import datadog.gradle.plugin.testJvmConstraints.CHECK_COVERAGE_ON_FIRST_TEST_JVM
import datadog.gradle.plugin.testJvmConstraints.checksCoverage
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.extra
import java.io.File

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
    parseSlot(slot)?.selects(path, "Project $path") ?: true
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
 * Creates a single aggregate root task that depends on matching subproject tasks.
 *
 * With [suiteSharding] and several active slots, a project whose aggregate runs more than its
 * default suite is distributed by suite groups (see [testShardGroups]). The group holding the
 * default suite keeps the project slot. JaCoCo projects keep project placement for their coverage
 * tasks and execution data. Git filtering selects the same project aggregates either way.
 */
private fun Project.createRootTask(
  rootTaskName: String,
  subProjTaskName: String,
  includePrefixes: List<String>,
  excludePrefixes: List<String>,
  forceCoverage: Boolean,
  suiteSharding: SuiteSharding? = null,
) {
  val checkCoverage = forceCoverage || rootProject.providers.gradleProperty(CHECK_COVERAGE).isPresent
  val checkCoverageOnFirstTestJvm = rootProject.providers.gradleProperty(CHECK_COVERAGE_ON_FIRST_TEST_JVM).isPresent
  val testJvm = rootProject.providers.gradleProperty("testJvm").orNull
  val sharding = rootProject.extensions.getByType(TestSharding::class.java)
  val activeSlot = sharding.slot
  tasks.register(rootTaskName) {
    val rootTaskPath = path
    val useGitChanges = rootProject.extra.get("useGitChanges") as Boolean
    subprojects.forEach { subproject ->
      if (
        !includePrefixes.any { subproject.path.startsWith(it) } ||
        excludePrefixes.any { subproject.path.startsWith(it) }
      ) {
        return@forEach
      }

      val splitSuites = suiteSharding != null &&
        activeSlot != null && activeSlot.total > 1 &&
        subproject.path !in sharding.jacocoProjects &&
        // Judged on registered names, so projects that stay in their project slot do not realize
        // their Test tasks on the other runners.
        suiteSharding.hasExtraSuite(subproject.tasks.withType(Test::class.java).names)
      val inProjectSlot = activeSlot == null || activeSlot.selects(subproject.path, "Project ${subproject.path}")
      if (!splitSuites && !inProjectSlot) {
        return@forEach
      }

      val aggregate = subproject.tasks.findByName(subProjTaskName)
      if (aggregate != null && useGitChanges) {
        val affectedTaskPath = findAffectedTaskPath(aggregate, project.affectedTasksByProject())
        if (affectedTaskPath == null) {
          logger.warn("Skipping ${aggregate.path} (not affected by changed files)")
          return@forEach
        }
        logger.warn("Selecting ${aggregate.path} (affected by $affectedTaskPath)")
      }

      if (splitSuites && aggregate != null) {
        val children = aggregate.taskDependencies.getDependencies(aggregate)
        val groups = aggregate.testShardGroups(children)
        if (groups != null) {
          for (group in groups) {
            val key = if (group.any { it.suiteName == suiteSharding.defaultSuite }) {
              subproject.path
            } else {
              "${subproject.path}:${group.minOf { it.suiteName }}"
            }
            val selected = activeSlot.selects(key, "Test suite group $key")
            sharding.recordGroup(rootTaskPath, key, group, selected)
            if (selected) {
              dependsOn(group.filter { it in children })
            }
          }
          return@forEach
        }
      }

      if (inProjectSlot) {
        if (aggregate != null) {
          dependsOn(aggregate)
        }
        if (subproject.checksCoverage(checkCoverage, checkCoverageOnFirstTestJvm, testJvm)) {
          subproject.tasks.findByName("jacocoTestReport")?.let { dependsOn(it) }
          subproject.tasks.findByName("jacocoTestCoverageVerification")?.let { dependsOn(it) }
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
  // The aggregates read the slot and git state this plugin sets up.
  pluginManager.apply("dd-trace-java.ci-jobs")
  createRootTask(
    "${baseTaskName}Test",
    "allTests",
    includePrefixes,
    excludePrefixes,
    forceCoverage,
    SuiteSharding(::isAllTestsTask, defaultSuite = "test"),
  )
  createRootTask(
    "${baseTaskName}LatestDepTest",
    "allLatestDepTests",
    includePrefixes,
    excludePrefixes,
    forceCoverage,
    SuiteSharding(::isAllLatestDepTestsTask, defaultSuite = "latestDepTest"),
  )
  createRootTask("${baseTaskName}Check", "check", includePrefixes, excludePrefixes, forceCoverage)
}
