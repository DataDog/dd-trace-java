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
 * Creates aggregate test tasks for CI
 * 
 * Creates three tasks for the given base name:
 * - ${baseTaskName}Test - runs allTests
 * - ${baseTaskName}LatestDepTest - runs allLatestDepTests
 * - ${baseTaskName}Check - runs check
 */
fun Project.testAggregate(
    baseTaskName: String,
    includePrefixes: List<String>,
    excludePrefixes: List<String>,
    forceCoverage: Boolean = false
) {
    fun createRootTask(rootTaskName: String, subProjTaskName: String) {
        val coverage = forceCoverage || rootProject.hasProperty("checkCoverage")
        val proj = this@testAggregate
        tasks.register(rootTaskName) {
            proj.subprojects.forEach { subproject ->
                val activePartition = subproject.extra.get("activePartition") as Boolean
                if (activePartition &&
                    includePrefixes.any { subproject.path.startsWith(it) } &&
                    !excludePrefixes.any { subproject.path.startsWith(it) }) {
                    
                    val testTask = subproject.tasks.findByName(subProjTaskName)
                    var isAffected = true
                    
                    if (testTask != null) {
                        val useGitChanges = proj.rootProject.extra.get("useGitChanges") as Boolean
                        if (useGitChanges) {
                            @Suppress("UNCHECKED_CAST")
                            val affectedProjects = proj.rootProject.extra.get("affectedProjects") as Map<Project, Set<String>>
                            val fileTrigger = isAffectedBy(testTask, affectedProjects)
                            if (fileTrigger != null) {
                                proj.logger.warn("Selecting ${subproject.path}:$subProjTaskName (triggered by $fileTrigger)")
                            } else {
                                proj.logger.warn("Skipping ${subproject.path}:$subProjTaskName (not affected by changed files)")
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
    
    createRootTask("${baseTaskName}Test", "allTests")
    createRootTask("${baseTaskName}LatestDepTest", "allLatestDepTests")
    createRootTask("${baseTaskName}Check", "check")
}

