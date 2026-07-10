package datadog.gradle.plugin.config

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSetOutput
import java.io.File

/** Validates that all config definitions in the config directory are documented in supported-configurations.json. */
internal class RegularConfigCheckAction(
  private val mainSourceSetOutput: Provider<Provider<SourceSetOutput>>,
  private val generatedClassName: Provider<String>,
  private val extension: SupportedTracerConfigurations
) : Action<Task> {
  override fun execute(task: Task) {
    val repoRoot = task.project.rootProject.projectDir.toPath()
    val configDir = repoRoot.resolve("dd-trace-api/src/main/java/datadog/trace/api/config").toFile()

    if (!configDir.exists()) {
      throw GradleException("Config directory not found: ${configDir.absolutePath}")
    }

    val configFields = loadConfigFields(mainSourceSetOutput.get().get(), generatedClassName.get())
    val supported = configFields.supported
    val aliasMapping = configFields.aliasMapping

    val violations = buildList {
      configDir.listFiles()?.forEach { file ->
        val fileName = file.name
        extractStringConstants(file).forEach eachConstant@{ (fieldName, entry) ->
          if (fieldName.endsWith("_DEFAULT")) return@eachConstant
          val normalized = normalize(entry.value)
          if (normalized !in supported && normalized !in aliasMapping) {
            add("$fileName:${entry.line} -> Config '${entry.value}' normalizes to '$normalized' " +
              "which is missing from '${extension.jsonFile.get()}'")
          }
        }
      }
    }

    if (violations.isNotEmpty()) {
      task.logger.error("\nFound config definitions not in '${extension.jsonFile.get()}':")
      violations.forEach { task.logger.lifecycle(it) }
      throw GradleException("Undocumented Environment Variables found. Please add the above Environment Variables to '${extension.jsonFile.get()}'.")
    } else {
      task.logger.info("All config strings are present in '${extension.jsonFile.get()}'.")
    }
  }
}

/**
 * Validates that every `.ddprof.` config key used as a primary key in `DatadogProfilerConfig`'s
 * static helpers also has its async-translated form (`profiling.ddprof.*` → `profiling.async.*`)
 * documented in `supported-configurations.json`.
 */
internal class ProfilingConfigCheckAction(
  private val mainSourceSetOutput: Provider<Provider<SourceSetOutput>>,
  private val generatedClassName: Provider<String>,
  private val extension: SupportedTracerConfigurations
) : Action<Task> {
  override fun execute(task: Task) {
    val repoRoot = task.project.rootProject.projectDir.toPath()

    val constantMap = extractStringConstants(
      repoRoot.resolve("dd-trace-api/src/main/java/datadog/trace/api/config/ProfilingConfig.java").toFile()
    )

    val configFields = loadConfigFields(mainSourceSetOutput.get().get(), generatedClassName.get())
    val supported = configFields.supported
    val aliasMapping = configFields.aliasMapping

    val ddprofConfigFile = repoRoot.resolve(
      "dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilerConfig.java"
    ).toFile()
    val cu = StaticJavaParser.parse(ddprofConfigFile)

    val helperMethodNames = setOf("getBoolean", "getInteger", "getLong", "getString")
    val violations = mutableListOf<String>()

    cu.findAll(MethodCallExpr::class.java).forEach { call ->
      if (call.scope.isPresent) return@forEach
      if (call.nameAsString !in helperMethodNames) return@forEach
      val args = call.arguments
      if (args.size < 2 || args[0] !is NameExpr || (args[0] as NameExpr).nameAsString != "configProvider") return@forEach

      val primaryKeyEntry = resolveConstant(args[1], constantMap) ?: return@forEach
      checkDocumented(primaryKeyEntry, supported, aliasMapping, call, violations, extension)
    }

    if (violations.isNotEmpty()) {
      violations.forEach { task.logger.error(it) }
      throw GradleException("Undocumented configs found in DatadogProfilerConfig. Please add the above to '${extension.jsonFile.get()}'.")
    } else {
      task.logger.info("All DatadogProfilerConfig configs are documented.")
    }
  }
}

internal data class ConstantEntry(val value: String, val line: Int)

internal fun extractStringConstants(file: File): Map<String, ConstantEntry> {
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

internal fun resolveConstant(expr: Expression?, constantMap: Map<String, ConstantEntry>): ConstantEntry? = when (expr) {
  is StringLiteralExpr -> ConstantEntry(expr.value, -1)
  is NameExpr -> constantMap[expr.nameAsString]
  else -> null
}

// Only check the async-translated form produced by DatadogProfilerConfig.normalizeKey.
internal fun checkDocumented(
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
