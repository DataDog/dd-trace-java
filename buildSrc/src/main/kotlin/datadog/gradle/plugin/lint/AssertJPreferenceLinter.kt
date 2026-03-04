package datadog.gradle.plugin.lint

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.ByteArrayOutputStream

class AssertJPreferenceLinter : Plugin<Project> {
  override fun apply(target: Project) {
    target.tasks.register("checkAssertJPreference") {
      group = "verification"
      description = "Flags JUnit assertion imports in new test files — prefer AssertJ"

      doLast {
        val newTestFiles = getNewTestFiles(target)
        if (newTestFiles.isEmpty()) {
          target.logger.lifecycle("checkAssertJPreference: No new test files to check")
          return@doLast
        }

        val warnings = mutableListOf<String>()
        val junitAssertImport = Regex("""import\s+(?:static\s+)?org\.junit\.jupiter\.api\.Assertions""")

        newTestFiles.forEach { file ->
          if (!file.exists()) return@forEach
          val lines = file.readLines()
          val relPath = file.relativeTo(target.rootProject.projectDir).path

          for (i in lines.indices) {
            if (junitAssertImport.containsMatchIn(lines[i])) {
              warnings.add("STYLE: $relPath:${i + 1} — Prefer AssertJ (org.assertj.core.api.Assertions) over JUnit assertions for richer API")
            }
          }
        }

        if (warnings.isNotEmpty()) {
          warnings.forEach { target.logger.warn(it) }
          target.logger.warn("checkAssertJPreference: ${warnings.size} file(s) using JUnit assertions instead of AssertJ")
        } else {
          target.logger.lifecycle("checkAssertJPreference: All new test files use AssertJ")
        }
      }
    }
  }

  private fun getNewTestFiles(project: Project): List<java.io.File> {
    return try {
      val stdout = ByteArrayOutputStream()
      project.exec {
        commandLine("git", "diff", "--name-only", "--diff-filter=A", "origin/master...HEAD")
        standardOutput = stdout
        isIgnoreExitValue = true
      }
      stdout.toString().trim().lines()
        .filter { it.endsWith(".java") && it.contains("src/test/") }
        .map { project.rootProject.file(it) }
    } catch (e: Exception) {
      emptyList()
    }
  }
}
