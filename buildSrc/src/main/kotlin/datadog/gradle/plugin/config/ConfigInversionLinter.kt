package datadog.gradle.plugin.config

import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.getByType
import java.net.URLClassLoader
import java.nio.file.Path

class ConfigInversionLinter : Plugin<Project> {
  override fun apply(target: Project) {
    val extension = target.extensions.create("supportedTracerConfigurations", SupportedTracerConfigurations::class.java)
    val logEnvVarUsages = registerLogEnvVarUsages(target, extension)
    val checkEnvVarUsage = registerCheckEnvironmentVariablesUsage(target)
    val checkConfigStrings = registerCheckConfigStringsTask(target, extension)
    val checkInstrumenterModule = registerCheckInstrumenterModuleConfigurations(target, extension)
    val checkDecoratorAnalytics = registerCheckDecoratorAnalyticsConfigurations(target, extension)

    target.tasks.register("checkConfigurations") {
      group = "verification"
      description = "Runs all config inversion validation checks"
      dependsOn(logEnvVarUsages, checkEnvVarUsage, checkConfigStrings, checkInstrumenterModule, checkDecoratorAnalytics)
    }
  }
}

// Data class for fields from generated class
data class LoadedConfigFields(
  val supported: Set<String>,
  val aliasMapping: Map<String, String> = emptyMap(),
  val aliases: Map<String, List<String>> = emptyMap()
)

// Cache for fields from generated class
internal var cachedConfigFields: LoadedConfigFields? = null

// Helper function to load fields from the generated class
internal fun loadConfigFields(
  mainSourceSetOutput: org.gradle.api.file.FileCollection,
  generatedClassName: String
): LoadedConfigFields {
  return cachedConfigFields ?: run {
    val urls = mainSourceSetOutput.files.map { it.toURI().toURL() }.toTypedArray()
    URLClassLoader(urls, LoadedConfigFields::class.java.classLoader).use { cl ->
      val clazz = Class.forName(generatedClassName, true, cl)

      val supportedField = clazz.getField("SUPPORTED").get(null)
      @Suppress("UNCHECKED_CAST")
      val supportedSet = when (supportedField) {
        is Set<*> -> supportedField as Set<String>
        is Map<*, *> -> supportedField.keys as Set<String>
        else -> throw IllegalStateException("SUPPORTED field must be either Set<String> or Map<String, Any>, but was ${supportedField?.javaClass}")
      }

      @Suppress("UNCHECKED_CAST")
      val aliasMappingMap = clazz.getField("ALIAS_MAPPING").get(null) as Map<String, String>
      @Suppress("UNCHECKED_CAST")
      val aliasesMap = clazz.getField("ALIASES").get(null) as Map<String, List<String>>
      LoadedConfigFields(supportedSet, aliasMappingMap, aliasesMap)
    }.also { cachedConfigFields = it }
  }
}

/** Registers `logEnvVarUsages` (scan for DD_/OTEL_ tokens and fail if unsupported). */
private fun registerLogEnvVarUsages(target: Project, extension: SupportedTracerConfigurations): TaskProvider<Task> {
  val ownerPath = extension.configOwnerPath
  val generatedFile = extension.className

  // token check that uses the generated class instead of JSON
  return target.tasks.register("logEnvVarUsages") {
    group = "verification"
    description = "Scan Java files for DD_/OTEL_ tokens and fail if unsupported (using generated constants)"

    val mainSourceSetOutput = ownerPath.map {
      target.project(it)
        .extensions.getByType<SourceSetContainer>()
        .named(SourceSet.MAIN_SOURCE_SET_NAME)
        .map { main -> main.output }
    }
    inputs.files(mainSourceSetOutput)

    // inputs for incrementality (your own source files, not the owner’s)
    val javaFiles = target.fileTree(target.projectDir) {
      include("**/src/main/java/**/*.java")
      exclude("**/build/**", "**/dd-smoke-tests/**")
      // Undertow uses DD_UNDERTOW_CONTINUATION as a legacy key to store an AgentScope. It is not related to an environment variable
      exclude("dd-java-agent/instrumentation/undertow/undertow-common/src/main/java/datadog/trace/instrumentation/undertow/UndertowDecorator.java")
    }
    inputs.files(javaFiles)
    outputs.upToDateWhen { true }
    doLast {
      // 1) Load configuration fields from the generated class
      val configFields = loadConfigFields(mainSourceSetOutput.get().get(), generatedFile.get())
      val supported = configFields.supported

      // 2) Scan our sources and compare
      val repoRoot = target.projectDir.toPath()
      val tokenRegex = Regex("\"(?:DD_|OTEL_)[A-Za-z0-9_]+\"")

      val violations = buildList {
        javaFiles.files.forEach { f ->
          val rel = repoRoot.relativize(f.toPath()).toString()
          var inBlock = false
          f.readLines().forEachIndexed { i, raw ->
            val trimmed = raw.trim()
            if (trimmed.startsWith("//")) return@forEachIndexed
            if (!inBlock && trimmed.contains("/*")) inBlock = true
            if (inBlock) {
              if (trimmed.contains("*/")) inBlock = false
              return@forEachIndexed
            }
            tokenRegex.findAll(raw).forEach { m ->
              val token = m.value.trim('"')
              if (token !in supported) add("$rel:${i + 1} -> Unsupported token '$token'")
            }
          }
        }
      }

      if (violations.isNotEmpty()) {
        violations.forEach { target.logger.error(it) }
        throw GradleException("Unsupported DD_/OTEL_ tokens found! See errors above.")
      } else {
        target.logger.info("All DD_/OTEL_ tokens are supported.")
      }
    }
  }
}

/** Registers `checkEnvironmentVariablesUsage` (forbid EnvironmentVariables.get(...)). */
private fun registerCheckEnvironmentVariablesUsage(project: Project): TaskProvider<Task> {
  return project.tasks.register("checkEnvironmentVariablesUsage") {
    group = "verification"
    description = "Scans src/main/java for direct usages of EnvironmentVariables.get(...)"

    doLast {
      val repoRoot: Path = project.projectDir.toPath()
      val javaFiles = project.fileTree(project.projectDir) {
        include("**/src/main/java/**/*.java")
        exclude("**/build/**")
        exclude("utils/config-utils/src/main/java/datadog/trace/config/inversion/ConfigHelper.java")
        exclude("dd-java-agent/agent-bootstrap/**")
        exclude("dd-java-agent/src/main/java/datadog/trace/bootstrap/**")
      }

      val pattern = Regex("""EnvironmentVariables\.get\s*\(""")
      val matches = buildList {
        javaFiles.forEach { f ->
          val relative = repoRoot.relativize(f.toPath())
          f.readLines().forEachIndexed { idx, line ->
            if (pattern.containsMatchIn(line)) {
              add("$relative:${idx + 1} -> ${line.trim()}")
            }
          }
        }
      }

      if (matches.isNotEmpty()) {
        project.logger.lifecycle("\nFound forbidden usages of EnvironmentVariables.get(...):")
        matches.forEach { project.logger.lifecycle(it) }
        throw GradleException("Forbidden usage of EnvironmentVariables.get(...) found in Java files.")
      } else {
        project.logger.info("No forbidden EnvironmentVariables.get(...) usages found in src/main/java.")
      }
    }
  }
}

// Helper functions for checking Config Strings
internal fun normalize(configValue: String) =
  "DD_" + configValue.uppercase().replace("-", "_").replace(".", "_")

// Checking "public" "static" "final"
internal fun NodeWithModifiers<*>.hasModifiers(vararg mods: Modifier.Keyword) =
  mods.all { hasModifier(it) }

/** Registers `checkConfigStrings` to validate config definitions against documented supported configurations. */
private fun registerCheckConfigStringsTask(project: Project, extension: SupportedTracerConfigurations): TaskProvider<Task> {
  val ownerPath = extension.configOwnerPath
  val generatedFile = extension.className

  return project.tasks.register("checkConfigStrings") {
    group = "verification"
    description = "Validates that all config definitions in `dd-trace-api/src/main/java/datadog/trace/api/config` exist in `metadata/supported-configurations.json`"

    val mainSourceSetOutput = ownerPath.map {
      project.project(it)
        .extensions.getByType<SourceSetContainer>()
        .named(SourceSet.MAIN_SOURCE_SET_NAME)
        .map { main -> main.output }
    }
    inputs.files(mainSourceSetOutput)

    doLast("regular-config-check", RegularConfigCheckAction(mainSourceSetOutput, generatedFile, extension))
    doLast("profiling-config-check", ProfilingConfigCheckAction(mainSourceSetOutput, generatedFile, extension))
  }
}


/** Registers `checkInstrumenterModuleConfigurations` to verify each InstrumenterModule's integration name has proper entries in SUPPORTED and ALIASES. */
private fun registerCheckInstrumenterModuleConfigurations(project: Project, extension: SupportedTracerConfigurations): TaskProvider<CheckInstrumenterModuleConfigTask> {
  val ownerPath = extension.configOwnerPath
  val generatedFile = extension.className

  return project.tasks.register("checkInstrumenterModuleConfigurations", CheckInstrumenterModuleConfigTask::class.java) {
    group = "verification"
    description = "Validates that InstrumenterModule integration names have corresponding entries in SUPPORTED and ALIASES"

    mainSourceSetOutput.from(ownerPath.map {
      project.project(it)
        .extensions.getByType<SourceSetContainer>()
        .named(SourceSet.MAIN_SOURCE_SET_NAME)
        .map { main -> main.output }
    })
    instrumentationFiles.from(project.fileTree(project.rootProject.projectDir) {
      include("dd-java-agent/instrumentation/**/src/main/java/**/*.java")
    })
    generatedClassName.set(generatedFile)
    errorHeader.set("\nFound InstrumenterModule integration names with missing SUPPORTED/ALIASES entries:")
    errorMessage.set("InstrumenterModule integration names are missing from SUPPORTED or ALIASES in '${extension.jsonFile.get()}'.")
    successMessage.set("All InstrumenterModule integration names have proper SUPPORTED and ALIASES entries.")
  }
}

/** Registers `checkDecoratorAnalyticsConfigurations` to verify each BaseDecorator subclass's instrumentationNames have proper analytics entries in SUPPORTED and ALIASES. */
private fun registerCheckDecoratorAnalyticsConfigurations(project: Project, extension: SupportedTracerConfigurations): TaskProvider<CheckDecoratorAnalyticsConfigTask> {
  val ownerPath = extension.configOwnerPath
  val generatedFile = extension.className

  return project.tasks.register("checkDecoratorAnalyticsConfigurations", CheckDecoratorAnalyticsConfigTask::class.java) {
    group = "verification"
    description = "Validates that Decorator instrumentationNames have corresponding analytics entries in SUPPORTED and ALIASES"

    mainSourceSetOutput.from(ownerPath.map {
      project.project(it)
        .extensions.getByType<SourceSetContainer>()
        .named(SourceSet.MAIN_SOURCE_SET_NAME)
        .map { main -> main.output }
    })
    instrumentationFiles.from(project.fileTree(project.rootProject.projectDir) {
      include("dd-java-agent/instrumentation/**/src/main/java/**/*.java")
    })
    generatedClassName.set(generatedFile)
    errorHeader.set("\nFound Decorator instrumentationNames with missing analytics SUPPORTED/ALIASES entries:")
    errorMessage.set("Decorator instrumentationNames are missing analytics entries from SUPPORTED or ALIASES in '${extension.jsonFile.get()}'.")
    successMessage.set("All Decorator instrumentationNames have proper analytics SUPPORTED and ALIASES entries.")
  }
}
