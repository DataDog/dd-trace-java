package datadog.gradle.plugin.lint

import org.gradle.api.Plugin
import org.gradle.api.Project

class NamingConventionLinter : Plugin<Project> {
  override fun apply(target: Project) {
    target.tasks.register("checkNamingConventions") {
      group = "verification"
      description = "Checks Java files changed vs. base branch for snake_case method/variable names"

      doLast {
        val repoRoot = target.rootProject.projectDir

        // Get changed .java files via git diff against base branch
        val gitOutput = try {
          val process = ProcessBuilder("git", "diff", "--name-only", "--diff-filter=ACM", "origin/master...HEAD")
            .directory(repoRoot)
            .redirectErrorStream(true)
            .start()
          process.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) {
          target.logger.warn("checkNamingConventions: could not run git diff — skipping. ${e.message}")
          return@doLast
        }

        if (gitOutput.isBlank()) {
          target.logger.lifecycle("checkNamingConventions: no changed files found.")
          return@doLast
        }

        val changedJavaFiles = gitOutput.lines()
          .filter { it.endsWith(".java") }
          .map { repoRoot.resolve(it) }
          .filter { it.exists() }

        if (changedJavaFiles.isEmpty()) {
          target.logger.lifecycle("checkNamingConventions: no changed .java files.")
          return@doLast
        }

        // Matches method-like declarations with underscores in the name
        // e.g. "void my_method(" or "public String some_name("
        val methodWithUnderscore = Regex(
          """(?:public|private|protected|static|void|int|long|boolean|String|\w+)\s+(\w*_\w+)\s*\("""
        )

        // Matches local variable declarations with underscores
        // e.g. "int my_var = " or "final String some_name;"
        val localVarWithUnderscore = Regex(
          """(?:int|long|String|boolean|var|final)\s+(\w*_\w+)\s*[=;]"""
        )

        // All-uppercase constant pattern: words with underscores, all caps
        val constantPattern = Regex("""^[A-Z][A-Z0-9_]*$""")

        val warnings = mutableListOf<String>()

        for (file in changedJavaFiles) {
          val relativePath = repoRoot.toPath().relativize(file.toPath()).toString()
          val isTestFile = relativePath.contains("/src/test/") || relativePath.contains("\\src\\test\\")
          val isMainFile = relativePath.contains("/src/main/") || relativePath.contains("\\src\\main\\")

          val lines = file.readLines()
          var pendingTestAnnotation = false

          for ((idx, line) in lines.withIndex()) {
            val trimmed = line.trim()
            val lineNumber = idx + 1

            // Track @Test annotations
            if (trimmed == "@Test" || trimmed.startsWith("@Test(")) {
              pendingTestAnnotation = true
              continue
            }
            // Reset annotation flag on non-annotation, non-empty lines
            if (trimmed.isNotEmpty() && !trimmed.startsWith("@") && !trimmed.startsWith("//")) {
              val hadTestAnnotation = pendingTestAnnotation
              pendingTestAnnotation = false

              // Check method names (applies to all files except when @Test annotated or in test dir)
              if (!isTestFile && !hadTestAnnotation) {
                methodWithUnderscore.find(line)?.let { match ->
                  val name = match.groupValues[1]
                  if (!constantPattern.matches(name) && !isNativeMethod(line)) {
                    warnings.add("NAMING: $relativePath:$lineNumber — snake_case identifier '$name' should be camelCase")
                  }
                }
              }

              // Check local variables (only in src/main/ files)
              if (isMainFile) {
                localVarWithUnderscore.find(line)?.let { match ->
                  val name = match.groupValues[1]
                  if (!constantPattern.matches(name)) {
                    warnings.add("NAMING: $relativePath:$lineNumber — snake_case identifier '$name' should be camelCase")
                  }
                }
              }
            } else if (trimmed.startsWith("@")) {
              // Keep the annotation flag active across annotation lines
            } else {
              pendingTestAnnotation = false
            }
          }
        }

        if (warnings.isNotEmpty()) {
          target.logger.warn("checkNamingConventions: found ${warnings.size} naming convention warning(s):")
          warnings.forEach { target.logger.warn(it) }
        } else {
          target.logger.lifecycle("checkNamingConventions: no naming convention issues found.")
        }
      }
    }
  }

  private fun isNativeMethod(line: String): Boolean {
    return line.contains("native ")
  }
}
