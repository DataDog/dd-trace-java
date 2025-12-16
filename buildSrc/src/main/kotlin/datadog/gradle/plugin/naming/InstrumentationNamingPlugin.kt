package datadog.gradle.plugin.naming

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
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
  override fun apply(target: Project) {
    val extension = target.extensions.create(
      "instrumentationNaming",
      InstrumentationNamingExtension::class.java
    )

    target.tasks.register("checkInstrumentationNaming") {
      group = "verification"
      description = "Validates naming conventions for instrumentation modules"

      doLast {
        val instrumentationsDir = target.rootProject.file(extension.instrumentationsDir.get())
        val exclusions = extension.exclusions.get().toSet()

        if (!instrumentationsDir.exists() || !instrumentationsDir.isDirectory) {
          throw GradleException(
            "Instrumentations directory not found: ${instrumentationsDir.absolutePath}"
          )
        }

        val violations = validateInstrumentations(instrumentationsDir, exclusions)

        if (violations.isNotEmpty()) {
          val errorMessage = buildString {
            appendLine("\nInstrumentation naming convention violations found:")
            appendLine()
            violations.forEach { violation ->
              appendLine("  • ${violation.path}")
              appendLine("    ${violation.message}")
              appendLine()
            }
            appendLine("Naming rules:")
            appendLine("  1. Module name must end with a version (e.g., '2.0', '3.1') OR end with '-common'")
            appendLine("  2. Module name must include the parent directory name")
            appendLine("     Example: 'couchbase/couchbase-2.0' ✓ (contains 'couchbase')")
            appendLine()
            appendLine("To exclude specific modules, configure the plugin:")
            appendLine("  instrumentationNaming {")
            appendLine("    exclusions.set(listOf(\"module-name\"))")
            appendLine("  }")
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
    exclusions: Set<String>
  ): List<NamingViolation> {
    val violations = mutableListOf<NamingViolation>()

    // Get all subdirectories in the instrumentations directory
    instrumentationsDir.listFiles { file -> file.isDirectory }?.forEach parentLoop@{ parentDir ->
      val parentName = parentDir.name

      // Skip build directories and other non-instrumentation directories
      if (parentName in setOf("build", "src", ".gradle")) {
        return@parentLoop
      }

      // Check if this directory has a build.gradle file
      // If it does, it's a leaf instrumentation module
      val hasBuildFile = parentDir.listFiles()?.any {
        it.name == "build.gradle" || it.name == "build.gradle.kts"
      } ?: false

      if (hasBuildFile) {
        // This is a leaf module, validate only the version/common suffix requirement
        if (parentName !in exclusions) {
          validateLeafModuleName(parentName, parentDir.relativeTo(instrumentationsDir).path)?.let {
            violations.add(it)
          }
        }
      } else {
        // This directory contains sub-modules, check each one
        parentDir.listFiles { file -> file.isDirectory }?.forEach moduleLoop@{ moduleDir ->
          val moduleName = moduleDir.name

          // Skip build and other non-module directories
          if (moduleName in setOf("build", "src", ".gradle")) {
            return@moduleLoop
          }

          // Check if this is actually a module (has build.gradle)
          val hasModuleBuildFile = moduleDir.listFiles()?.any {
            it.name == "build.gradle" || it.name == "build.gradle.kts"
          } ?: false

          if (hasModuleBuildFile && moduleName !in exclusions) {
            validateModuleName(moduleName, parentName, moduleDir.relativeTo(instrumentationsDir).path)?.let {
              violations.add(it)
            }
          }
        }
      }
    }

    return violations
  }

  private fun validateModuleName(
    moduleName: String,
    parentName: String,
    relativePath: String
  ): NamingViolation? {
    // Rule 1: Module name must end with version pattern (X.Y, X.Y.Z, etc.) or "-common"
    val versionPattern = Regex("""\d+\.\d+(\.\d+)?$""")
    val endsWithCommon = moduleName.endsWith("-common")
    val endsWithVersion = versionPattern.containsMatchIn(moduleName)

    if (!endsWithVersion && !endsWithCommon) {
      return NamingViolation(
        relativePath,
        "Module name '$moduleName' must end with a version (e.g., '2.0', '3.1.0') or '-common'"
      )
    }

    // Rule 2: Module name must contain parent directory name
    // Extract the base name (without version or -common suffix)
    if (!moduleName.contains(parentName, ignoreCase = true)) {
      return NamingViolation(
        relativePath,
        "Module name '$moduleName' should contain parent directory name '$parentName'"
      )
    }

    return null
  }

  /**
   * Validates naming for leaf modules (modules at the top level with no parent grouping).
   * These only need to check the version/common suffix requirement.
   */
  private fun validateLeafModuleName(
    moduleName: String,
    relativePath: String
  ): NamingViolation? {
    // Rule: Module name must end with version pattern (X.Y, X.Y.Z, etc.) or "-common"
    val versionPattern = Regex("""\d+\.\d+(\.\d+)?$""")
    val endsWithCommon = moduleName.endsWith("-common")
    val endsWithVersion = versionPattern.containsMatchIn(moduleName)

    if (!endsWithVersion && !endsWithCommon) {
      return NamingViolation(
        relativePath,
        "Module name '$moduleName' must end with a version (e.g., '2.0', '3.1.0') or '-common'"
      )
    }

    return null
  }

  private data class NamingViolation(
    val path: String,
    val message: String
  )
}
