package datadog.gradle.plugin.lint

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.ByteArrayOutputStream

class EmptyInstrumentationLinter : Plugin<Project> {
  override fun apply(target: Project) {
    target.tasks.register("checkEmptyInstrumentations") {
      group = "verification"
      description = "Detects empty instrumentation stub classes with no transform() calls in methodAdvice()"

      doLast {
        val instrumentationsDir = target.rootProject.file("dd-java-agent/instrumentation")

        if (!instrumentationsDir.exists() || !instrumentationsDir.isDirectory) {
          throw GradleException(
            "Instrumentations directory not found: ${instrumentationsDir.absolutePath}"
          )
        }

        // Only check files changed on this branch to avoid flagging existing stubs
        val changedFiles = getChangedInstrumentationFiles(target, instrumentationsDir)

        val violations = mutableListOf<String>()
        val hasAdvicePattern = Regex("""implements\s+[^{]*\b(?:HasMethodAdvice|HasAdvice)\b""")
        val methodAdviceStartPattern = Regex("""(?:public\s+)?(?:\S+\s+)?methodAdvice\s*\(""")
        val transformCallPattern = Regex("""\btransform\s*\(""")

        val filesToCheck = if (changedFiles != null) {
          changedFiles.filter { it.isFile && it.name.endsWith(".java") }
        } else {
          // Fallback: check all files if git diff fails
          instrumentationsDir.walk()
            .filter { it.isFile && it.name.endsWith(".java") }
            .toList()
        }

        filesToCheck.forEach { file ->
          val lines = file.readLines()
          val content = lines.joinToString("\n")

          if (!hasAdvicePattern.containsMatchIn(content)) return@forEach

          var methodAdviceLineIndex = -1
          for (i in lines.indices) {
            if (methodAdviceStartPattern.containsMatchIn(lines[i])) {
              methodAdviceLineIndex = i
              break
            }
          }

          if (methodAdviceLineIndex < 0) return@forEach

          var braceDepth = 0
          var methodBodyStart = -1
          val methodBodyLines = mutableListOf<String>()

          for (i in methodAdviceLineIndex until lines.size) {
            val line = lines[i]
            for (ch in line) {
              when (ch) {
                '{' -> {
                  braceDepth++
                  if (braceDepth == 1) methodBodyStart = i
                }
                '}' -> {
                  braceDepth--
                  if (braceDepth == 0 && methodBodyStart >= 0) {
                    break
                  }
                }
              }
            }
            if (methodBodyStart >= 0) {
              methodBodyLines.add(line)
            }
            if (braceDepth == 0 && methodBodyStart >= 0) break
          }

          val methodBody = methodBodyLines.joinToString("\n")
          if (!transformCallPattern.containsMatchIn(methodBody)) {
            val classNamePattern = Regex("""class\s+(\w+)""")
            val className = classNamePattern.find(content)?.groupValues?.get(1) ?: "<unknown>"
            val relPath = file.relativeTo(target.rootProject.projectDir).path
            violations.add("EMPTY STUB: $relPath:${methodAdviceLineIndex + 1} — $className.methodAdvice() contains no transform() calls")
          }
        }

        if (violations.isNotEmpty()) {
          violations.forEach { target.logger.error(it) }
          throw GradleException("Found ${violations.size} new empty instrumentation stub(s)! See errors above.")
        } else {
          target.logger.lifecycle("checkEmptyInstrumentations: no new empty stubs found")
        }
      }
    }
  }

  private fun getChangedInstrumentationFiles(project: Project, instrumentationsDir: java.io.File): List<java.io.File>? {
    return try {
      val stdout = ByteArrayOutputStream()
      project.exec {
        commandLine("git", "diff", "--name-only", "--diff-filter=ACM", "origin/master...HEAD")
        standardOutput = stdout
        isIgnoreExitValue = true
      }
      stdout.toString().trim().lines()
        .filter { it.endsWith(".java") && it.startsWith("dd-java-agent/instrumentation/") }
        .map { project.rootProject.file(it) }
        .filter { it.exists() }
    } catch (e: Exception) {
      project.logger.warn("checkEmptyInstrumentations: could not get changed files, checking all — ${e.message}")
      null
    }
  }
}
