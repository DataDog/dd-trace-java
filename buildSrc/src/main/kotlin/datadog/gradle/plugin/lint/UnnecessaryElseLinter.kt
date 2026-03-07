package datadog.gradle.plugin.lint

import org.gradle.api.Plugin
import org.gradle.api.Project

class UnnecessaryElseLinter : Plugin<Project> {
  override fun apply(target: Project) {
    target.tasks.register("checkUnnecessaryElse") {
      group = "verification"
      description = "Scan changed Java files for unnecessary else blocks after return/throw/continue/break"

      doLast {
        val changedFiles = getChangedJavaFiles(target)
        val repoRoot = target.rootProject.projectDir.toPath()
        val warnings = mutableListOf<String>()

        changedFiles.forEach { file ->
          if (file.exists()) {
            val relPath = repoRoot.relativize(file.toPath()).toString()
            checkFile(file, relPath, warnings)
          }
        }

        if (warnings.isNotEmpty()) {
          warnings.forEach { target.logger.warn(it) }
          target.logger.warn("Found ${warnings.size} unnecessary else block(s) — advisory only.")
        } else {
          target.logger.info("No unnecessary else blocks found in changed files.")
        }
      }
    }
  }
}

private fun getChangedJavaFiles(project: Project): List<java.io.File> {
  return try {
    val process = ProcessBuilder("git", "diff", "--name-only", "--diff-filter=ACM", "origin/master...HEAD")
      .directory(project.rootProject.projectDir)
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor()
    output.trim().lines()
      .filter { it.isNotBlank() && it.endsWith(".java") }
      .map { project.rootProject.projectDir.resolve(it) }
      .filter { it.exists() }
  } catch (e: Exception) {
    project.logger.warn("checkUnnecessaryElse: could not get changed files — ${e.message}")
    emptyList()
  }
}

private fun checkFile(file: java.io.File, relPath: String, warnings: MutableList<String>) {
  val lines = file.readLines()
  val elsePattern = Regex("""^\s*\}\s*else\s*(\{.*)?$""")

  for (i in lines.indices) {
    if (!elsePattern.containsMatchIn(lines[i])) continue
    if (isUnnecessaryElse(lines, i)) {
      warnings.add("STYLE: $relPath:${i + 1} — unnecessary else after return/throw/continue/break")
    }
  }
}

private fun isUnnecessaryElse(lines: List<String>, elseLineIdx: Int): Boolean {
  // Walk backwards from "} else {" to find the last meaningful statement
  var j = elseLineIdx - 1
  while (j >= 0) {
    val trimmed = lines[j].trim()
    if (trimmed.isEmpty() || isCommentLine(trimmed)) {
      j--
      continue
    }
    // The last non-empty line before "} else {" must end with ";" to be a statement boundary
    if (!trimmed.endsWith(";")) return false

    // Check if this single line is itself an exit statement
    if (isExitStatement(trimmed)) return true

    // For multi-line statements (e.g. multi-line return/throw), walk further back
    // looking for the start of the statement (a line not ending with a continuation)
    var k = j - 1
    while (k >= 0) {
      val prev = lines[k].trim()
      if (prev.isEmpty() || isCommentLine(prev)) {
        k--
        continue
      }
      // Another complete statement or block boundary — stop
      if (prev.endsWith(";") || prev.endsWith("{") || prev.endsWith("}")) return false
      // This line is a continuation of the same statement
      if (startsWithExitKeyword(prev)) return true
      k--
    }
    return false
  }
  return false
}

private fun isCommentLine(trimmed: String) =
  trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")

private fun isExitStatement(trimmed: String) =
  startsWithExitKeyword(trimmed)

private fun startsWithExitKeyword(trimmed: String) =
  trimmed.startsWith("return ") || trimmed == "return;" ||
    trimmed.startsWith("throw ") ||
    trimmed == "continue;" || trimmed.startsWith("continue ") ||
    trimmed == "break;" || trimmed.startsWith("break ")
