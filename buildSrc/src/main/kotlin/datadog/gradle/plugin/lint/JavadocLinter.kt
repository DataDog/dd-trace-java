package datadog.gradle.plugin.lint

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.ByteArrayOutputStream

class JavadocLinter : Plugin<Project> {
  override fun apply(target: Project) {
    target.tasks.register("checkJavadocQuality") {
      group = "verification"
      description = "Detects empty @return and @param tags in Javadoc comments"

      doLast {
        val changedFiles = getChangedJavaFiles(target)
        if (changedFiles.isEmpty()) {
          target.logger.lifecycle("checkJavadocQuality: No changed Java files to check")
          return@doLast
        }

        val warnings = mutableListOf<String>()
        val emptyReturnPattern = Regex("""@return\s*$""")
        val emptyParamPattern = Regex("""@param\s+\w+\s*$""")

        changedFiles.forEach { file ->
          if (!file.exists()) return@forEach
          val lines = file.readLines()
          val relPath = file.relativeTo(target.rootProject.projectDir).path

          for (i in lines.indices) {
            val trimmed = lines[i].trim().removePrefix("* ").removePrefix("*")
            if (emptyReturnPattern.containsMatchIn(trimmed)) {
              // Check next non-empty line isn't a continuation
              val nextContent = lines.getOrNull(i + 1)?.trim()?.removePrefix("* ")?.removePrefix("*")?.trim() ?: ""
              if (nextContent.isEmpty() || nextContent.startsWith("@") || nextContent.startsWith("*/")) {
                warnings.add("JAVADOC: $relPath:${i + 1} — empty @return tag")
              }
            }
            if (emptyParamPattern.containsMatchIn(trimmed)) {
              val nextContent = lines.getOrNull(i + 1)?.trim()?.removePrefix("* ")?.removePrefix("*")?.trim() ?: ""
              if (nextContent.isEmpty() || nextContent.startsWith("@") || nextContent.startsWith("*/")) {
                warnings.add("JAVADOC: $relPath:${i + 1} — empty @param tag")
              }
            }
          }
        }

        if (warnings.isNotEmpty()) {
          warnings.forEach { target.logger.warn(it) }
          target.logger.warn("checkJavadocQuality: ${warnings.size} Javadoc warning(s) found")
        } else {
          target.logger.lifecycle("checkJavadocQuality: No empty Javadoc tags found")
        }
      }
    }
  }

  private fun getChangedJavaFiles(project: Project): List<java.io.File> {
    return try {
      val stdout = ByteArrayOutputStream()
      project.exec {
        commandLine("git", "diff", "--name-only", "--diff-filter=ACMR", "origin/master...HEAD")
        standardOutput = stdout
        isIgnoreExitValue = true
      }
      stdout.toString().trim().lines()
        .filter { it.endsWith(".java") }
        .map { project.rootProject.file(it) }
    } catch (e: Exception) {
      emptyList()
    }
  }
}
