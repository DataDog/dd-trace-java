package datadog.gradle.plugin.lint

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

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

        val violations = mutableListOf<String>()
        val hasAdvicePattern = Regex("""implements\s+[^{]*\b(?:HasMethodAdvice|HasAdvice)\b""")
        val methodAdviceStartPattern = Regex("""(?:public\s+)?(?:\S+\s+)?methodAdvice\s*\(""")
        val transformCallPattern = Regex("""\btransform\s*\(""")

        instrumentationsDir.walk()
          .filter { it.isFile && it.name.endsWith(".java") }
          .forEach { file ->
            val lines = file.readLines()
            val content = lines.joinToString("\n")

            if (!hasAdvicePattern.containsMatchIn(content)) return@forEach

            // Find methodAdvice( method and check its body for transform( calls
            var methodAdviceLineIndex = -1
            for (i in lines.indices) {
              if (methodAdviceStartPattern.containsMatchIn(lines[i])) {
                methodAdviceLineIndex = i
                break
              }
            }

            if (methodAdviceLineIndex < 0) return@forEach

            // Extract method body by tracking brace depth
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
                      methodBodyLines.add(line)
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
          throw GradleException("Empty instrumentation stubs found! See errors above.")
        } else {
          target.logger.lifecycle("✓ No empty instrumentation stubs found")
        }
      }
    }
  }
}
