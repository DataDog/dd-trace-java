package datadog.gradle.plugin.config

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.getByType
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path

class ConfigInversionLinter : Plugin<Project> {
  override fun apply(target: Project) {
    val extension = target.extensions.create("supportedTracerConfigurations", SupportedTracerConfigurations::class.java)
    registerLogEnvVarUsages(target, extension)
    registerCheckEnvironmentVariablesUsage(target)
    registerCheckConfigStringsTask(target, extension)
    registerCheckDatadogProfilerConfigTask(target, extension)
  }
}

// Data class for fields from generated class
private data class LoadedConfigFields(
  val supported: Set<String>,
  val aliasMapping: Map<String, String> = emptyMap()
)

// Cache for fields from generated class
private var cachedConfigFields: LoadedConfigFields? = null

// Helper function to load fields from the generated class
private fun loadConfigFields(
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
      LoadedConfigFields(supportedSet, aliasMappingMap)
    }.also { cachedConfigFields = it }
  }
}

/** Registers `logEnvVarUsages` (scan for DD_/OTEL_ tokens and fail if unsupported). */
private fun registerLogEnvVarUsages(target: Project, extension: SupportedTracerConfigurations) {
  val ownerPath = extension.configOwnerPath
  val generatedFile = extension.className

  // token check that uses the generated class instead of JSON
  target.tasks.register("logEnvVarUsages") {
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
private fun registerCheckEnvironmentVariablesUsage(project: Project) {
  project.tasks.register("checkEnvironmentVariablesUsage") {
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
private fun normalize(configValue: String) =
  "DD_" + configValue.uppercase().replace("-", "_").replace(".", "_")

// Checking "public" "static" "final"
private fun NodeWithModifiers<*>.hasModifiers(vararg mods: Modifier.Keyword) =
  mods.all { hasModifier(it) }

/** Registers `checkConfigStrings` to validate config definitions against documented supported configurations. */
private fun registerCheckConfigStringsTask(project: Project, extension: SupportedTracerConfigurations) {
  val ownerPath = extension.configOwnerPath
  val generatedFile = extension.className

  project.tasks.register("checkConfigStrings") {
    group = "verification"
    description = "Validates that all config definitions in `dd-trace-api/src/main/java/datadog/trace/api/config` exist in `metadata/supported-configurations.json`"

    val mainSourceSetOutput = ownerPath.map {
      project.project(it)
        .extensions.getByType<SourceSetContainer>()
        .named(SourceSet.MAIN_SOURCE_SET_NAME)
        .map { main -> main.output }
    }
    inputs.files(mainSourceSetOutput)

    doLast {
      val repoRoot: Path = project.rootProject.projectDir.toPath()
      val configDir = repoRoot.resolve("dd-trace-api/src/main/java/datadog/trace/api/config").toFile()

      if (!configDir.exists()) {
        throw GradleException("Config directory not found: ${configDir.absolutePath}")
      }

      val configFields = loadConfigFields(mainSourceSetOutput.get().get(), generatedFile.get())
      val supported = configFields.supported
      val aliasMapping = configFields.aliasMapping

      val violations = buildList {
        configDir.listFiles()?.forEach { file ->
          val fileName = file.name
          extractStringConstants(file).forEach { (fieldName, entry) ->
            if (fieldName.endsWith("_DEFAULT")) return@forEach
            val normalized = normalize(entry.value)
            if (normalized !in supported && normalized !in aliasMapping) {
              add("$fileName:${entry.line} -> Config '${entry.value}' normalizes to '$normalized' " +
                "which is missing from '${extension.jsonFile.get()}'")
            }
          }
        }
      }

      if (violations.isNotEmpty()) {
        logger.error("\nFound config definitions not in '${extension.jsonFile.get()}':")
        violations.forEach { logger.lifecycle(it) }
        throw GradleException("Undocumented Environment Variables found. Please add the above Environment Variables to '${extension.jsonFile.get()}'.")
      } else {
        logger.info("All config strings are present in '${extension.jsonFile.get()}'.")
      }
    }
  }
}

/**
 * Registers `checkDatadogProfilerConfigs` to validate that every `.ddprof.` config key used as a
 * primary key in `DatadogProfilerConfig`'s static helpers also has its async-translated form
 * (`profiling.ddprof.*` → `profiling.async.*`) documented in `supported-configurations.json`.
 *
 * The raw form is already validated by `checkConfigStrings`. This task only covers the additional
 * async form produced by `DatadogProfilerConfig.normalizeKey`.
 */
private fun registerCheckDatadogProfilerConfigTask(project: Project, extension: SupportedTracerConfigurations) {
  val ownerPath = extension.configOwnerPath
  val generatedFile = extension.className

  project.tasks.register("checkDatadogProfilerConfigs") {
    group = "verification"
    description = "Validates all configs read in DatadogProfilerConfig are documented in supported-configurations.json"

    val mainSourceSetOutput = ownerPath.map {
      project.project(it)
        .extensions.getByType<SourceSetContainer>()
        .named(SourceSet.MAIN_SOURCE_SET_NAME)
        .map { main -> main.output }
    }
    inputs.files(mainSourceSetOutput)

    doLast {
      val repoRoot = project.rootProject.projectDir.toPath()

      // Only ProfilingConfig.java is needed — all .ddprof. keys are defined there
      val constantMap = extractStringConstants(
        repoRoot.resolve("dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java").toFile()
      )

      val configFields = loadConfigFields(mainSourceSetOutput.get().get(), generatedFile.get())
      val supported = configFields.supported
      val aliasMapping = configFields.aliasMapping

      val ddprofConfigFile = repoRoot.resolve(
        "dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java"
      ).toFile()
      val cu = StaticJavaParser.parse(ddprofConfigFile)

      val helperMethodNames = setOf("getBoolean", "getInteger", "getLong", "getString")
      val violations = mutableListOf<String>()

      cu.findAll(MethodCallExpr::class.java).forEach { call ->
        // Only care about static helper calls: getXxx(configProvider, primaryKey, default, ...aliases)
        // Direct configProvider.getXxx() calls are already covered by checkConfigStrings
        if (call.scope.isPresent) return@forEach
        if (call.nameAsString !in helperMethodNames) return@forEach
        val args = call.arguments
        if (args.size < 2 || args[0] !is NameExpr || (args[0] as NameExpr).nameAsString != "configProvider") return@forEach

        // Primary key goes through normalizeKey — validate its async-translated form
        val primaryKeyEntry = resolveConstant(args[1], constantMap) ?: return@forEach
        checkDocumented(primaryKeyEntry, supported, aliasMapping, call, violations, extension)
      }

      if (violations.isNotEmpty()) {
        violations.forEach { logger.error(it) }
        throw GradleException("Undocumented configs found in DatadogProfilerConfig. Please add the above to '${extension.jsonFile.get()}'.")
      } else {
        logger.info("All DatadogProfilerConfig configs are documented.")
      }
    }
  }
}

private data class ConstantEntry(val value: String, val line: Int)

private fun extractStringConstants(file: File): Map<String, ConstantEntry> {
  val map = mutableMapOf<String, ConstantEntry>()
  StaticJavaParser.parse(file).findAll(VariableDeclarator::class.java).forEach { varDecl ->
    val field = varDecl.parentNode.map { it as? FieldDeclaration }.orElse(null) ?: return@forEach
    if (field.hasModifiers(Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL)
      && varDecl.typeAsString == "String") {
      val init = varDecl.initializer.orElse(null) as? StringLiteralExpr ?: return@forEach
      val line = varDecl.range.map { it.begin.line }.orElse(-1)
      map[varDecl.nameAsString] = ConstantEntry(init.value, line)
    }
  }
  return map
}

private fun resolveConstant(expr: Expression?, constantMap: Map<String, ConstantEntry>): ConstantEntry? = when (expr) {
  is StringLiteralExpr -> ConstantEntry(expr.value, -1)
  is NameExpr -> constantMap[expr.nameAsString]
  else -> null
}

// Only check the async-translated form produced by DatadogProfilerConfig.normalizeKey.
private fun checkDocumented(
  entry: ConstantEntry,
  supported: Set<String>,
  aliasMapping: Map<String, String>,
  call: MethodCallExpr,
  violations: MutableList<String>,
  extension: SupportedTracerConfigurations
) {
  if (!entry.value.contains(".ddprof.")) return
  val asyncNormalized = normalize(entry.value.replace(".ddprof.", ".async."))
  if (asyncNormalized !in supported && asyncNormalized !in aliasMapping) {
    val callLine = call.range.map { it.begin.line }.orElse(-1)
    violations.add("ProfilingConfig.java:${entry.line} (DatadogProfilerConfig.java:$callLine) -> '${entry.value}' (async form) → '$asyncNormalized' is missing from '${extension.jsonFile.get()}'")
  }
}
