package datadog.gradle.plugin.config

import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers
import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.GradleException
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
    verifyAliasKeysAreSupported(target, extension)
  }
}

// Data class for fields from generated class
private data class LoadedConfigFields(
  val supported: Set<String>,
  val aliases: Map<String, List<String>> = emptyMap(),
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
      val aliases = clazz.getField("ALIASES").get(null) as Map<String, List<String>>

      @Suppress("UNCHECKED_CAST")
      val aliasMappingMap = clazz.getField("ALIAS_MAPPING").get(null) as Map<String, String>

      LoadedConfigFields(supportedSet, aliases, aliasMappingMap)
    }.also { cachedConfigFields = it }
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


/** Registers `verifyAliasKeysAreSupported` to ensure all alias keys are documented as supported configurations. */
private fun verifyAliasKeysAreSupported(project: Project, extension: SupportedTracerConfigurations) {
  val ownerPath = extension.configOwnerPath
  val generatedFile = extension.className

  project.tasks.register("verifyAliasKeysAreSupported") {
    group = "verification"
    description =
      "Verifies that all alias keys in `metadata/supported-configurations.json` are also documented as supported configurations."

    val mainSourceSetOutput = ownerPath.map {
      project.project(it)
        .extensions.getByType<SourceSetContainer>()
        .named(SourceSet.MAIN_SOURCE_SET_NAME)
        .map { main -> main.output }
    }
    inputs.files(mainSourceSetOutput)

    doLast {
      val configFields = loadConfigFields(mainSourceSetOutput.get().get(), generatedFile.get())
      val supported = configFields.supported
      val aliases = configFields.aliases.keys

      val unsupportedAliasKeys = aliases - supported
      val violations = buildList {
        unsupportedAliasKeys.forEach { key ->
          add("$key is listed as an alias key but is not documented as a supported configuration in the `supportedConfigurations` key")
        }
      }
      if (violations.isNotEmpty()) {
        logger.error("\nFound alias keys not documented as supported configurations:")
        violations.forEach { logger.lifecycle(it) }
        throw GradleException("Undocumented alias keys found. Please add the above keys to the `supportedConfigurations` in '${extension.jsonFile.get()}'.")
      } else {
        logger.info("All alias keys are documented as supported configurations.")
      }
    }
  }
}
