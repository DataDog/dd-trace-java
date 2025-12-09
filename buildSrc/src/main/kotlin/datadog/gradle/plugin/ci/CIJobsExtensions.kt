package datadog.gradle.plugin.ci

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.extra

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
 * Recursively finds all Test tasks in the dependency tree
 */
private fun collectTestTasks(task: Task): Set<Test> {
  val testTasks = mutableSetOf<Test>()
  val visited = mutableSetOf<Task>()
  val queue = mutableListOf(task)

  while (queue.isNotEmpty()) {
    val current = queue.removeAt(0)
    if (visited.contains(current)) {
      continue
    }
    visited.add(current)

    if (current is Test) {
      testTasks.add(current)
    }

    current.taskDependencies.getDependencies(current).forEach { dep ->
      queue.add(dep)
    }
  }

  return testTasks
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
  val rootTask = tasks.register(rootTaskName) {
    val allTestTasks = mutableSetOf<Test>()

    subprojects.forEach { subproject ->
      val activePartition = subproject.extra.get("activePartition") as Boolean
      if (
        activePartition &&
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
            // Collect all Test tasks from this dependency during configuration time
            allTestTasks.addAll(collectTestTasks(testTask))
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

    // Store collected test tasks for the finalizer
    extra.set("collectedTestTasks", allTestTasks.toList())
  }

  // Create a finalizer task that always runs and checks for test failures
  tasks.register("${rootTaskName}Finalizer") {
    mustRunAfter(rootTask)

    doLast {
      val mainTask = tasks.named(rootTaskName).get()
      @Suppress("UNCHECKED_CAST")
      val includedTestTasks = mainTask.extra.get("collectedTestTasks") as List<Test>

      logger.warn("Checking test results for ${includedTestTasks.size} test tasks")

      val failedTests = includedTestTasks.filter { testTask ->
        val didFail = testTask.state.failure != null ||
                      testTask.state.outcome?.name == "FAILED"

        if (testTask.state.executed) {
          logger.warn("Task ${testTask.project.path}:${testTask.name} - executed: true, outcome: ${testTask.state.outcome?.name}, failure: ${testTask.state.failure != null}")
        }

        didFail
      }

      if (failedTests.isNotEmpty()) {
        val failedTaskPaths = failedTests.map { "${it.project.path}:${it.name}" }
        val errorMsg = "Tests failed in: \n${failedTaskPaths.joinToString("\n")}"
        logger.error(errorMsg)
        throw GradleException(errorMsg)
      } else {
        logger.warn("All ${includedTestTasks.size} test tasks passed")
      }
    }
  }

  rootTask.configure {
    finalizedBy("${rootTaskName}Finalizer")
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

