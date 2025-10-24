/*
 * This plugin defines a set of tasks to be used in CI. These aggregate tasks support partitioning (to parallelize
 * jobs) with -PtaskPartitionCount and -PtaskPartition, and limiting tasks to those affected by git changes
 * with -PgitBaseRef.
 */

import datadog.gradle.plugin.ci.isAffectedBy
import java.io.File
import kotlin.math.abs

// Set up activePartition property on all projects
allprojects {
    extra.set("activePartition", true)
    
    val shouldUseTaskPartitions = rootProject.hasProperty("taskPartitionCount") && rootProject.hasProperty("taskPartition")
    if (shouldUseTaskPartitions) {
        val taskPartitionCount = rootProject.property("taskPartitionCount") as String
        val taskPartition = rootProject.property("taskPartition") as String
        val currentTaskPartition = abs(project.path.hashCode() % taskPartitionCount.toInt())
        extra.set("activePartition", currentTaskPartition == taskPartition.toInt())
    }
}

fun relativeToGitRoot(f: File): File {
    return rootProject.projectDir.toPath().relativize(f.absoluteFile.toPath()).toFile()
}

fun getChangedFiles(baseRef: String, newRef: String): List<File> {
    val stdout = StringBuilder()
    val stderr = StringBuilder()

    val proc = Runtime.getRuntime().exec(arrayOf("git", "diff", "--name-only", "$baseRef..$newRef"))
    proc.inputStream.bufferedReader().use { stdout.append(it.readText()) }
    proc.errorStream.bufferedReader().use { stderr.append(it.readText()) }
    proc.waitFor()
    require(proc.exitValue() == 0) { "git diff command failed, stderr: $stderr" }

    val out = stdout.toString().trim()
    if (out.isEmpty()) {
        return emptyList()
    }

    logger.debug("git diff output: $out")
    return out.split("\n").map { File(rootProject.projectDir, it.trim()) }
}

// Initialize git change tracking
rootProject.extra.set("useGitChanges", false)

if (rootProject.hasProperty("gitBaseRef")) {
    val baseRef = rootProject.property("gitBaseRef") as String
    val newRef = if (rootProject.hasProperty("gitNewRef")) {
        rootProject.property("gitNewRef") as String
    } else {
        "HEAD"
    }
    
    val changedFiles = getChangedFiles(baseRef, newRef)
    rootProject.extra.set("changedFiles", changedFiles)
    rootProject.extra.set("useGitChanges", true)
    
    val ignoredFiles = fileTree(rootProject.projectDir) {
        include(".gitignore", ".editorconfig")
        include("*.md", "**/*.md")
        include("gradlew", "gradlew.bat", "mvnw", "mvnw.cmd")
        include("NOTICE")
        include("static-analysis.datadog.yml")
    }

    changedFiles.forEach { f ->
        if (ignoredFiles.contains(f)) {
            logger.warn("Ignoring changed file: ${relativeToGitRoot(f)}")
        }
    }

    val filteredChangedFiles = changedFiles.filter { !ignoredFiles.contains(it) }
    rootProject.extra.set("changedFiles", filteredChangedFiles)
    
    val globalEffectFiles = fileTree(rootProject.projectDir) {
        include(".gitlab/**")
        include("build.gradle")
        include("gradle/**")
    }
    
    for (f in filteredChangedFiles) {
        if (globalEffectFiles.contains(f)) {
            logger.warn("Global effect change: ${relativeToGitRoot(f)} (no tasks will be skipped)")
            rootProject.extra.set("useGitChanges", false)
            break
        }
    }
    
    if (rootProject.extra.get("useGitChanges") as Boolean) {
        logger.warn("Git change tracking is enabled: $baseRef..$newRef")
        
        val projects = subprojects.sortedByDescending { it.projectDir.path.length }
        val affectedProjects = mutableMapOf<Project, MutableSet<String>>()

        // Path prefixes mapped to affected task names. A file not matching any of these prefixes will affect all tasks in
        // the project ("all" can be used a task name to explicitly state the same). Only the first matching prefix is used.
        val matchers = listOf(
            mapOf("prefix" to "src/testFixtures/", "task" to "testFixturesClasses"),
            mapOf("prefix" to "src/test/", "task" to "testClasses"),
            mapOf("prefix" to "src/jmh/", "task" to "jmhCompileGeneratedClasses")
        )

        for (f in filteredChangedFiles) {
            val p = projects.find { f.toString().startsWith(it.projectDir.path + "/") }
            if (p == null) {
                logger.warn("Changed file: ${relativeToGitRoot(f)} at root project (no task will be skipped)")
                rootProject.extra.set("useGitChanges", false)
                break
            }

            // Make sure path separator is /
            val relPath = p.projectDir.toPath().relativize(f.toPath()).joinToString("/")
            val task = matchers.find { relPath.startsWith(it["prefix"]!!) }?.get("task") ?: "all"
            logger.warn("Changed file: ${relativeToGitRoot(f)} in project ${p.path} ($task)")
            affectedProjects.computeIfAbsent(p) { mutableSetOf() }.add(task)
        }
        
        rootProject.extra.set("affectedProjects", affectedProjects)
    }
}

tasks.register("runMuzzle") {
    val muzzleSubprojects = subprojects.filter { p ->
        val activePartition = p.extra.get("activePartition") as Boolean
        activePartition && p.plugins.hasPlugin("java") && p.plugins.hasPlugin("muzzle")
    }
    dependsOn(muzzleSubprojects.map { p -> "${p.path}:muzzle" })
}
