package datadog.gradle.plugin.ci

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.kotlin.dsl.extra

/**
 * Checks if a task is affected by git changes
 */
internal fun isAffectedBy(baseTask: Task, affectedProjects: Map<Project, Set<String>>): String? {
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
    val coverage = forceCoverage || rootProject.hasProperty("checkCoverage")
    tasks.register(rootTaskName) {
        subprojects.forEach { subproject ->
            val activePartition = subproject.extra.get("activePartition") as Boolean
            if (activePartition &&
                includePrefixes.any { subproject.path.startsWith(it) } &&
                !excludePrefixes.any { subproject.path.startsWith(it) }) {
                
                val testTask = subproject.tasks.findByName(subProjTaskName)
                var isAffected = true
                
                if (testTask != null) {
                    val useGitChanges = rootProject.extra.get("useGitChanges") as Boolean
                    if (useGitChanges) {
                        @Suppress("UNCHECKED_CAST")
                        val affectedProjects = rootProject.extra.get("affectedProjects") as Map<Project, Set<String>>
                        val fileTrigger = isAffectedBy(testTask, affectedProjects)
                        if (fileTrigger != null) {
                            logger.warn("Selecting ${subproject.path}:$subProjTaskName (triggered by $fileTrigger)")
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

