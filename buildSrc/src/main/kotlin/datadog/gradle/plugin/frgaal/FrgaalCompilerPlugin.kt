package datadog.gradle.plugin.frgaal

import datadog.gradle.plugin.frgaal.FrgaalCompilerPlugin.Companion.SOURCE_VERSION
import datadog.gradle.plugin.frgaal.FrgaalCompilerPlugin.Companion.TARGET_VERSION
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.withType
import org.gradle.plugins.ide.idea.model.IdeaLanguageLevel
import org.gradle.plugins.ide.idea.model.IdeaModel
import java.io.File
import java.util.Locale

/**
 * Compiles test sources with the [Frgaal](https://frgaal.org) compiler so they may use modern Java
 * syntax (Java 17 source level) while still producing Java 8 bytecode that runs on every JVM the
 * agent supports.
 *
 * Caveats — read before rolling this out widely:
 * - **Sugar only.** Only syntax that desugars to Java 8 bytecode is safe: text blocks, `var`, switch
 *   expressions, `instanceof` patterns. Features that need newer *runtime* classes (records, sealed
 *   classes, pattern matching for `switch`) compile but fail at runtime on a Java 8 target. Treat the
 *   17 source level as "nicer syntax", not "all of Java 17".
 * - **No incremental compilation.** Forking with a custom `javac` executable opts out of Gradle's
 *   incremental Java compiler, so affected test source sets always recompile in full. Acceptable for
 *   a handful of modules; measure before applying repo-wide.
 * - **Frgaal runs on the Gradle daemon JDK** ([SOURCE_VERSION] source, [TARGET_VERSION] target).
 */
class FrgaalCompilerPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    configureIdeaLanguageLevel(project)

    val frgaalCompiler = project.configurations.create("frgaalCompiler")
    project.dependencies.add(frgaalCompiler.name, "org.frgaal:compiler:$FRGAAL_VERSION")

    val isWindows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
    val frgaalJavaHome = project.layout.buildDirectory.dir("frgaal-java-home")
    val frgaalJavacWrapper = frgaalJavaHome.map { it.file(if (isWindows) "bin/javac.bat" else "bin/javac") }

    val writeFrgaalJavacWrapper = project.tasks.register("writeFrgaalJavacWrapper") {
      inputs.files(frgaalCompiler)
      outputs.dir(frgaalJavaHome)

      doLast {
        val binDir = File(frgaalJavaHome.get().asFile, "bin")
        binDir.mkdirs()

        val realJava = File(System.getProperty("java.home"), if (isWindows) "bin/java.exe" else "bin/java").absolutePath
        val javacWrapper = frgaalJavacWrapper.get().asFile
        // When forkOptions.executable points at <frgaalJavaHome>/bin/javac, Gradle treats
        // <frgaalJavaHome> as a Java installation and validates it by running <home>/bin/java -version
        // (this happens on JDK 8 daemons, where gradle/java_no_deps.gradle resolves a javaCompiler
        // toolchain instead of using --release). So the fake home must expose a working java that
        // delegates to the real, probe-able JDK — otherwise the build fails before javac even runs.
        val javaWrapper = File(binDir, if (isWindows) "java.bat" else "java")
        if (isWindows) {
          javaWrapper.writeText(windowsJavaScript(realJava))
          javacWrapper.writeText(windowsWrapperScript(realJava, frgaalCompiler.asPath))
        } else {
          javaWrapper.writeText(unixJavaScript(realJava))
          javacWrapper.writeText(unixWrapperScript(realJava, frgaalCompiler.asPath))
          javaWrapper.setExecutable(true)
          javacWrapper.setExecutable(true)
        }
      }
    }

    // Registered from afterEvaluate so this configureEach runs *after* the shared compiler config in
    // gradle/java_no_deps.gradle (which sets options.release in its own configureEach). configureEach
    // actions run at task realization in registration order, so registering last lets us win and
    // clear the release flag — Frgaal needs source > target, which --release forbids.
    project.afterEvaluate {
      project.tasks.withType<JavaCompile>().configureEach {
        if (isTestJavaCompileTask(name)) {
          dependsOn(writeFrgaalJavacWrapper)
          sourceCompatibility = SOURCE_VERSION.toString()
          targetCompatibility = TARGET_VERSION.toString()
          options.release.set(null as Int?)
          options.isFork = true
          options.forkOptions.executable = frgaalJavacWrapper.get().asFile.absolutePath
          if (!options.compilerArgs.contains("-Xlint:-options")) {
            options.compilerArgs.add("-Xlint:-options")
          }
        }
      }
    }
  }

  /**
   * Tell IntelliJ (via its Gradle import) that the module accepts Java 17 source while still
   * targeting Java 8 bytecode, matching what Frgaal does for the test compile tasks. Without this
   * the IDE imports the module's language level/SDK from the `java` extension (Java 8) and flags
   * text blocks and other modern syntax as errors.
   */
  private fun configureIdeaLanguageLevel(project: Project) {
    project.pluginManager.apply("idea")
    project.extensions.configure<IdeaModel>("idea") {
      module.jdkName = SOURCE_VERSION.majorVersion
      module.languageLevel = IdeaLanguageLevel("JDK_${SOURCE_VERSION.majorVersion}")
      module.targetBytecodeVersion = TARGET_VERSION
    }
  }

  private fun isTestJavaCompileTask(taskName: String): Boolean {
    return taskName == "compileTestJava" ||
        (taskName.startsWith("compile") && taskName.endsWith("TestJava"))
  }

  /** Minimal `java` that forwards to the real JDK so Gradle's installation probe succeeds. */
  private fun unixJavaScript(realJava: String): String {
    return """
      |#!/bin/sh
      |exec ${shQuote(realJava)} "${'$'}@"
      |
      """.trimMargin()
  }

  private fun windowsJavaScript(realJava: String): String {
    return """
      |@echo off
      |"$realJava" %*
      |exit /b %ERRORLEVEL%
      |
      """.trimMargin()
  }

  private fun unixWrapperScript(javaExecutable: String, frgaalClasspath: String): String {
    return """
      |#!/usr/bin/env bash
      |set -euo pipefail
      |
      |jvm_args=()
      |javac_args=()
      |for arg in "${'$'}@"; do
      |  if [[ "${'$'}arg" == -J* ]]; then
      |    jvm_args+=("${'$'}{arg:2}")
      |  else
      |    javac_args+=("${'$'}arg")
      |  fi
      |done
      |
      |exec ${shQuote(javaExecutable)} "${'$'}{jvm_args[@]}" -cp ${shQuote(frgaalClasspath)} org.frgaal.Main "${'$'}{javac_args[@]}"
      |
      """.trimMargin()
  }

  private fun windowsWrapperScript(javaExecutable: String, frgaalClasspath: String): String {
    // -J-prefixed args go to the JVM; everything else (including @argfiles) goes to Frgaal.
    return """
      |@echo off
      |setlocal enabledelayedexpansion
      |set "JVM_ARGS="
      |set "JAVAC_ARGS="
      |:frgaal_parse
      |if "%~1"=="" goto frgaal_run
      |set "frgaal_raw=%~1"
      |if "!frgaal_raw:~0,2!"=="-J" (
      |  set "JVM_ARGS=!JVM_ARGS! !frgaal_raw:~2!"
      |) else (
      |  set "JAVAC_ARGS=!JAVAC_ARGS! %1"
      |)
      |shift
      |goto frgaal_parse
      |:frgaal_run
      |"$javaExecutable" !JVM_ARGS! -cp "$frgaalClasspath" org.frgaal.Main !JAVAC_ARGS!
      |exit /b %ERRORLEVEL%
      |
      """.trimMargin()
  }

  private fun shQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
  }

  companion object {
    private const val FRGAAL_VERSION = "25.0.0"
    private val SOURCE_VERSION = JavaVersion.VERSION_17
    private val TARGET_VERSION = JavaVersion.VERSION_1_8
  }
}
