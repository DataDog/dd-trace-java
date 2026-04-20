package datadog.gradle.plugin.config

import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction

/** Abstract base for tasks that scan instrumentation source files against the generated config class. */
abstract class InstrumentationConfigCheckTask : DefaultTask() {
  @get:InputFiles
  abstract val mainSourceSetOutput: ConfigurableFileCollection

  @get:InputFiles
  abstract val instrumentationFiles: ConfigurableFileCollection

  @get:Input
  abstract val generatedClassName: Property<String>

  @get:Input
  abstract val errorHeader: Property<String>

  @get:Input
  abstract val errorMessage: Property<String>

  @get:Input
  abstract val successMessage: Property<String>

  @TaskAction
  fun execute() {
    val configFields = loadConfigFields(mainSourceSetOutput, generatedClassName.get())

    val parserConfig = ParserConfiguration()
    parserConfig.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8)
    StaticJavaParser.setConfiguration(parserConfig)

    val repoRoot = project.rootProject.projectDir.toPath()
    val violations = instrumentationFiles.files.flatMap { file ->
      val rel = repoRoot.relativize(file.toPath()).toString()
      val cu: CompilationUnit = try {
        StaticJavaParser.parse(file)
      } catch (_: Exception) {
        return@flatMap emptyList()
      }
      collectPropertyViolations(configFields, rel, cu)
    }

    if (violations.isNotEmpty()) {
      logger.error(errorHeader.get())
      violations.forEach { logger.lifecycle(it) }
      throw GradleException(errorMessage.get())
    } else {
      logger.info(successMessage.get())
    }
  }

  protected abstract fun collectPropertyViolations(
    configFields: LoadedConfigFields, relativePath: String, cu: CompilationUnit
  ): List<String>

  /** Collects violations for [key] against [supported] and [aliases], checking that all [expectedAliases] are values of that alias entry. */
  protected fun collectMissingKeysAndAliases(
    key: String,
    expectedAliases: List<String>,
    supported: Set<String>,
    aliases: Map<String, List<String>>,
    location: String,
    context: String
  ): List<String> = buildList {
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
}

/** Checks that InstrumenterModule integration names have proper entries in SUPPORTED and ALIASES. */
abstract class CheckInstrumenterModuleConfigTask : InstrumentationConfigCheckTask() {
  override fun collectPropertyViolations(
    configFields: LoadedConfigFields, relativePath: String, cu: CompilationUnit
  ): List<String> {
    val violations = mutableListOf<String>()

    cu.findAll(ClassOrInterfaceDeclaration::class.java).forEach classLoop@{ classDecl ->
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
            val location = "$relativePath:$line"

            violations.addAll(collectMissingKeysAndAliases(
              enabledKey,
              listOf("DD_TRACE_INTEGRATION_${normalized}_ENABLED", "DD_INTEGRATION_${normalized}_ENABLED"),
              configFields.supported, configFields.aliases, location, context
            ))
          }
        }
    }

    return violations
  }
}

/** Checks that Decorator instrumentationNames have proper analytics entries in SUPPORTED and ALIASES. */
abstract class CheckDecoratorAnalyticsConfigTask : InstrumentationConfigCheckTask() {
  override fun collectPropertyViolations(
    configFields: LoadedConfigFields, relativePath: String, cu: CompilationUnit
  ): List<String> {
    val violations = mutableListOf<String>()

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
          val location = "$relativePath:$line"

          violations.addAll(collectMissingKeysAndAliases(
            "DD_TRACE_${normalized}_ANALYTICS_ENABLED",
            listOf("DD_${normalized}_ANALYTICS_ENABLED"),
            configFields.supported, configFields.aliases, location, context
          ))
          violations.addAll(collectMissingKeysAndAliases(
            "DD_TRACE_${normalized}_ANALYTICS_SAMPLE_RATE",
            listOf("DD_${normalized}_ANALYTICS_SAMPLE_RATE"),
            configFields.supported, configFields.aliases, location, context
          ))
        }
      }

    return violations
  }
}
