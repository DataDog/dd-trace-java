package datadog.gradle.plugin.lint

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

class CopyPasteDetectorPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    target.tasks.register("checkCodeDuplication") {
      group = "verification"
      description = "Detect copy-pasted code using hash-based method body comparison"

      doLast {
        val dirs = listOf("dd-java-agent/instrumentation", "dd-trace-core", "internal-api")
          .map { target.rootProject.file(it) }
          .filter { it.exists() }

        val methodHashes = mutableMapOf<Int, MutableList<String>>()
        var totalFiles = 0

        dirs.forEach { dir ->
          dir.walkTopDown()
            .filter { it.isFile && it.extension == "java" && !it.path.contains("/build/") && !it.path.contains("/generated/") }
            .forEach { file ->
              totalFiles++
              extractMethodBodies(file).forEach { (name, body) ->
                val normalized = normalizeCode(body)
                if (normalized.length > 200) {
                  val hash = normalized.hashCode()
                  val location = "${file.relativeTo(target.rootProject.projectDir).path}:$name"
                  methodHashes.getOrPut(hash) { mutableListOf() }.add(location)
                }
              }
            }
        }

        val duplicates = methodHashes.filter { it.value.size > 1 }
        if (duplicates.isNotEmpty()) {
          target.logger.warn("CPD: Found ${duplicates.size} group(s) of duplicate methods across $totalFiles files:")
          duplicates.entries.take(20).forEach { (_, locations) ->
            target.logger.warn("  DUPLICATE GROUP (${locations.size} copies):")
            locations.forEach { loc -> target.logger.warn("    - $loc") }
          }
          if (duplicates.size > 20) {
            target.logger.warn("  ... and ${duplicates.size - 20} more groups")
          }
        } else {
          target.logger.lifecycle("CPD: No significant duplicates found across $totalFiles files")
        }
      }
    }
  }

  private fun extractMethodBodies(file: File): List<Pair<String, String>> {
    val content = file.readText()
    val results = mutableListOf<Pair<String, String>>()
    val methodPattern = Regex("""(?:public|private|protected|static|final|synchronized|\s)+[\w<>\[\],\s]+\s+(\w+)\s*\([^)]*\)[^{]*\{""")

    methodPattern.findAll(content).forEach { match ->
      val methodName = match.groupValues[1]
      val startIdx = match.range.last + 1
      var braceCount = 1
      var idx = startIdx
      while (idx < content.length && braceCount > 0) {
        when (content[idx]) {
          '{' -> braceCount++
          '}' -> braceCount--
        }
        idx++
      }
      if (braceCount == 0) {
        val body = content.substring(startIdx, idx - 1)
        if (body.lines().size >= 5) {
          results.add(methodName to body)
        }
      }
    }
    return results
  }

  private fun normalizeCode(code: String): String {
    return code.lines()
      .map { it.trim() }
      .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") }
      .joinToString("\n")
      .replace(Regex("""\s+"""), " ")
  }
}
