package datadog.gradle.plugin.config

import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.getByType
import java.net.URLClassLoader
import java.nio.file.Path

class ConfigInversionLinter : Plugin<Project> {
  override fun apply(target: Project) {
    val extension = target.extensions.create("supportedTracerConfigurations", SupportedTracerConfigurations::class.java)
    registerLogEnvVarUsages(target, extension)
    registerCheckEnvironmentVariablesUsage(target)
    registerCheckConfigStringsTask(target, extension)
    registerCheckInstrumenterModuleConfigurations(target, extension)
    registerCheckDecoratorAnalyticsConfigurations(target, extension)
  }
}

// Data class for fields from generated class
private data class LoadedConfigFields(
  val supported: Set<String>,
  val aliasMapping: Map<String, String> = emptyMap(),
  val aliases: Map<String, List<String>> = emptyMap()
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
      @Suppress("UNCHECKED_CAST")
      val aliasesMap = clazz.getField("ALIASES").get(null) as Map<String, List<String>>
      LoadedConfigFields(supportedSet, aliasMappingMap, aliasesMap)
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

      var parserConfig = ParserConfiguration()
      parserConfig.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8)

      StaticJavaParser.setConfiguration(parserConfig)

      val violations = buildList {
        configDir.listFiles()?.forEach { file ->
          val fileName = file.name
          val cu: CompilationUnit = StaticJavaParser.parse(file)

          cu.findAll(VariableDeclarator::class.java).forEach { varDecl ->
            varDecl.parentNode
              .map { it as? FieldDeclaration }
              .ifPresent { field ->
                if (field.hasModifiers(Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL) &&
                  varDecl.typeAsString == "String") {

                  val fieldName = varDecl.nameAsString
                  if (fieldName.endsWith("_DEFAULT")) return@ifPresent
                  val init = varDecl.initializer.orElse(null) ?: return@ifPresent

                  if (init !is StringLiteralExpr) return@ifPresent
                  val rawValue = init.value

                  val normalized = normalize(rawValue)
                  if (normalized !in supported && normalized !in aliasMapping) {
                    val line = varDecl.range.map { it.begin.line }.orElse(1)
                    add("$fileName:$line -> Config '$rawValue' normalizes to '$normalized' " +
                        "which is missing from '${extension.jsonFile.get()}'")
                  }
                }
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

/** Checks that [key] exists in [supported] and [aliases], and that all [expectedAliases] are values of that alias entry. */
private fun MutableList<String>.checkKeyAndAliases(
  key: String,
  expectedAliases: List<String>,
  supported: Set<String>,
  aliases: Map<String, List<String>>,
  location: String,
  context: String
) {
  if (key !in supported) {
    add("$location -> $context: '$key' is missing from SUPPORTED")
  }
  if (key !in aliases) {
    add("$location -> $context: '$key' is missing from ALIASES")
  } else {
    val aliasValues = aliases[key] ?: emptyList()
    for (expected in expectedAliases) {
      if (expected !in aliasValues) {
        add("$location -> $context: '$expected' is missing from ALIASES['$key']")
      }
    }
  }
}

/**
 * Shared setup for tasks that scan instrumentation source files against the generated config class.
 * Registers a task that parses Java files in dd-java-agent/instrumentation/ and calls [checker]
 * for each parsed CompilationUnit to collect violations.
 */
private fun registerInstrumentationCheckTask(
  project: Project,
  extension: SupportedTracerConfigurations,
  taskName: String,
  taskDescription: String,
  errorHeader: String,
  errorMessage: String,
  successMessage: String,
  checker: MutableList<String>.(LoadedConfigFields, String, CompilationUnit) -> Unit
) {
  val ownerPath = extension.configOwnerPath
  val generatedFile = extension.className

  project.tasks.register(taskName) {
    group = "verification"
    description = taskDescription

    val mainSourceSetOutput = ownerPath.map {
      project.project(it)
        .extensions.getByType<SourceSetContainer>()
        .named(SourceSet.MAIN_SOURCE_SET_NAME)
        .map { main -> main.output }
    }
    inputs.files(mainSourceSetOutput)

    val instrumentationFiles = project.fileTree(project.rootProject.projectDir) {
      include("dd-java-agent/instrumentation/**/src/main/java/**/*.java")
    }
    doLast {
      val configFields = loadConfigFields(mainSourceSetOutput.get().get(), generatedFile.get())

      val parserConfig = ParserConfiguration()
      parserConfig.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8)
      StaticJavaParser.setConfiguration(parserConfig)

      val repoRoot = project.rootProject.projectDir.toPath()
      val violations = buildList {
        instrumentationFiles.files.forEach { file ->
          val rel = repoRoot.relativize(file.toPath()).toString()
          val cu: CompilationUnit = try {
            StaticJavaParser.parse(file)
          } catch (_: Exception) {
            return@forEach
          }
          checker(configFields, rel, cu)
        }
      }

      if (violations.isNotEmpty()) {
        logger.error(errorHeader)
        violations.forEach { logger.lifecycle(it) }
        throw GradleException(errorMessage)
      } else {
        logger.info(successMessage)
      }
    }
  }
}

/** Registers `checkInstrumenterModuleConfigurations` to verify each InstrumenterModule's integration name has proper entries in SUPPORTED and ALIASES. */
private fun registerCheckInstrumenterModuleConfigurations(project: Project, extension: SupportedTracerConfigurations) {
  registerInstrumentationCheckTask(
    project, extension,
    taskName = "checkInstrumenterModuleConfigurations",
    taskDescription = "Validates that InstrumenterModule integration names have corresponding entries in SUPPORTED and ALIASES",
    errorHeader = "\nFound InstrumenterModule integration names with missing SUPPORTED/ALIASES entries:",
    errorMessage = "InstrumenterModule integration names are missing from SUPPORTED or ALIASES in '${extension.jsonFile.get()}'.",
    successMessage = "All InstrumenterModule integration names have proper SUPPORTED and ALIASES entries."
  ) { configFields, rel, cu ->
    cu.findAll(ClassOrInterfaceDeclaration::class.java).forEach classLoop@{ classDecl ->
      // Only examine classes extending InstrumenterModule.*
      val extendsModule = classDecl.extendedTypes.any { it.toString().startsWith("InstrumenterModule") }
      if (!extendsModule) return@classLoop

      classDecl.findAll(ExplicitConstructorInvocationStmt::class.java)
        .filter { !it.isThis }
        .forEach { superCall ->
          val names = superCall.arguments
            .filterIsInstance<StringLiteralExpr>()
            .map { it.value }
          val line = superCall.range.map { it.begin.line }.orElse(1)

          for (name in names) {
            val normalized = name.uppercase().replace("-", "_").replace(".", "_")
            val enabledKey = "DD_TRACE_${normalized}_ENABLED"
            val context = "Integration '$name' (super arg)"
            val location = "$rel:$line"

            checkKeyAndAliases(
              enabledKey,
              listOf("DD_TRACE_INTEGRATION_${normalized}_ENABLED", "DD_INTEGRATION_${normalized}_ENABLED"),
              configFields.supported, configFields.aliases, location, context
            )
          }
        }
    }
  }
}

/** Registers `checkDecoratorAnalyticsConfigurations` to verify each BaseDecorator subclass's instrumentationNames have proper analytics entries in SUPPORTED and ALIASES. */
private fun registerCheckDecoratorAnalyticsConfigurations(project: Project, extension: SupportedTracerConfigurations) {
  registerInstrumentationCheckTask(
    project, extension,
    taskName = "checkDecoratorAnalyticsConfigurations",
    taskDescription = "Validates that Decorator instrumentationNames have corresponding analytics entries in SUPPORTED and ALIASES",
    errorHeader = "\nFound Decorator instrumentationNames with missing analytics SUPPORTED/ALIASES entries:",
    errorMessage = "Decorator instrumentationNames are missing analytics entries from SUPPORTED or ALIASES in '${extension.jsonFile.get()}'.",
    successMessage = "All Decorator instrumentationNames have proper analytics SUPPORTED and ALIASES entries."
  ) { configFields, rel, cu ->
    cu.findAll(MethodDeclaration::class.java)
      .filter { it.nameAsString == "instrumentationNames" && it.parameters.isEmpty() }
      .forEach { method ->
        val names = method.findAll(ReturnStmt::class.java).flatMap { ret ->
          ret.expression.map { it.findAll(StringLiteralExpr::class.java).map { s -> s.value } }
            .orElse(emptyList())
        }
        val line = method.range.map { it.begin.line }.orElse(1)

        for (name in names) {
          val normalized = name.uppercase().replace("-", "_").replace(".", "_")
          val context = "Decorator instrumentationName '$name'"
          val location = "$rel:$line"

          checkKeyAndAliases(
            "DD_TRACE_${normalized}_ANALYTICS_ENABLED",
            listOf("DD_${normalized}_ANALYTICS_ENABLED"),
            configFields.supported, configFields.aliases, location, context
          )
          checkKeyAndAliases(
            "DD_TRACE_${normalized}_ANALYTICS_SAMPLE_RATE",
            listOf("DD_${normalized}_ANALYTICS_SAMPLE_RATE"),
            configFields.supported, configFields.aliases, location, context
          )
        }
      }
  }
}
