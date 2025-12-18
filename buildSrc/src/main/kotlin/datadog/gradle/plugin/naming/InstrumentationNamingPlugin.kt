package datadog.gradle.plugin.naming

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import java.io.File

/**
 * Plugin that validates naming conventions for instrumentation modules.
 *
 * Rules:
 * 1. Module name must end with a version (e.g., "2.0", "3.1") OR end with "-common"
 * 2. Module name must include the parent directory name
 *    (e.g., "couchbase-2.0" must contain "couchbase" which is the parent directory name)
 *
 * Apply this plugin:
 * ```
 * plugins {
 *   id("dd-trace-java.instrumentation-naming")
 * }
 * ```
 */
class InstrumentationNamingPlugin : Plugin<Project> {
  private val versionPattern : Regex = Regex("""\d+\.\d+(\.\d+)?$""")

  override fun apply(target: Project) {
    val extension = target.extensions.create<InstrumentationNamingExtension>("instrumentationNaming")

    target.tasks.register("checkInstrumentationNaming") {
      group = "verification"
      description = "Validates naming conventions for instrumentation modules"

      doLast {
        val instrumentationsDir = target.rootProject.file(extension.instrumentationsDir)
        val exclusions = extension.exclusions.get()
        val suffixes = extension.suffixes.get()

        if (!instrumentationsDir.exists() || !instrumentationsDir.isDirectory) {
          throw GradleException(
            "Instrumentations directory not found: ${instrumentationsDir.absolutePath}"
          )
        }

        val violations = validateInstrumentations(instrumentationsDir, exclusions, suffixes)

        if (violations.isNotEmpty()) {
          val suffixesStr = suffixes.joinToString("', '", "'", "'")
          val errorMessage = buildString {
            appendLine("""
            
            Instrumentation naming convention violations found:
            
            """.trimIndent())
            violations.forEach { violation ->
              appendLine("""
                • ${violation.path}
                  ${violation.message}
              """.trimEndent())
            }
            append("""
              Naming rules:
                1. Module name must end with a version (e.g., '2.0', '3.1') OR one of: $suffixesStr
                2. Module name must include the parent directory name
                   Example: 'couchbase/couchbase-2.0' ✓ (contains 'couchbase')

              To exclude specific modules or customize suffixes, configure the plugin:
                instrumentationNaming {
                  exclusions.set(setOf("module-name"))
                  suffixes.set(setOf("-common", "-stubs"))
                }
              """.trimIndent())
          }
          throw GradleException(errorMessage)
        } else {
          target.logger.lifecycle("✓ All instrumentation modules follow naming conventions")
        }
      }
    }
  }

  private fun validateInstrumentations(
    instrumentationsDir: File,
    exclusions: Set<String>,
    suffixes: Set<String>
  ): List<NamingViolation> {
    val violations = mutableListOf<NamingViolation>()

    fun hasBuildFile(dir: File): Boolean = dir.listFiles()?.any {
      it.name == "build.gradle" || it.name == "build.gradle.kts"
    } ?: false

    fun traverseModules(currentDir: File, parentName: String?) {
      currentDir.listFiles { file -> file.isDirectory }?.forEach childLoop@{ childDir ->
        val moduleName = childDir.name

        // Skip build directories and other non-instrumentation directories
        if (moduleName in setOf("build", "src", ".gradle")) {
          return@childLoop
        }

        val childHasBuildFile = hasBuildFile(childDir)
        val nestedModules = childDir.listFiles { file -> file.isDirectory }?.filter { hasBuildFile(it) } ?: emptyList()

        if (childHasBuildFile && moduleName !in exclusions) {
          val relativePath = childDir.relativeTo(instrumentationsDir).path
          if (parentName == null && nestedModules.isEmpty()) {
            validateLeafModuleName(moduleName, relativePath, suffixes)?.let { violations.add(it) }
          } else if (parentName != null) {
            violations.addAll(validateModuleName(moduleName, parentName, relativePath, suffixes))
          }
        }

        // Continue traversing to validate deeply nested modules
        if (nestedModules.isNotEmpty() || !childHasBuildFile) {
          traverseModules(childDir, moduleName)
        }
      }
    }

    traverseModules(instrumentationsDir, null)

    return violations
  }

  private fun validateModuleName(
    moduleName: String,
    parentName: String,
    relativePath: String,
    suffixes: Set<String>
  ): List<NamingViolation> {
    // Rule 1: Module name must end with version pattern or one of the configured suffixes
    validateVersionOrSuffix(moduleName, relativePath, suffixes)?.let { return listOf(it) }

    // Rule 2: Module name must contain parent directory name
    if (!moduleName.contains(parentName, ignoreCase = true)) {
      return listOf(NamingViolation(
        relativePath,
        "Module name '$moduleName' should contain parent directory name '$parentName'"
      ))
    }

    return emptyList()
  }

  /**
   * Validates naming for leaf modules (modules at the top level with no parent grouping).
   * These only need to check the version/suffix requirement.
   */
  private fun validateLeafModuleName(
    moduleName: String,
    relativePath: String,
    suffixes: Set<String>
  ): NamingViolation? {
    return validateVersionOrSuffix(moduleName, relativePath, suffixes)
  }

  /**
   * Validates that a module name ends with either a version or one of the configured suffixes.
   */
  private fun validateVersionOrSuffix(
    moduleName: String,
    relativePath: String,
    suffixes: Set<String>
  ): NamingViolation? {
    val endsWithSuffix = suffixes.any { moduleName.endsWith(it) }
    val endsWithVersion = versionPattern.containsMatchIn(moduleName)

    if (!endsWithVersion && !endsWithSuffix) {
      val suffixesStr = suffixes.joinToString("', '", "'", "'")
      return NamingViolation(
        relativePath,
        "Module name '$moduleName' must end with a version (e.g., '2.0', '3.1.0') or one of: $suffixesStr"
      )
    }

    return null
  }

  private data class NamingViolation(
    val path: String,
    val message: String
  )
}
