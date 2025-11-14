package datadog.gradle.plugin.testJvmConstraints

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.internal.provider.PropertyFactory
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JavaToolchainSpec
import org.gradle.jvm.toolchain.JvmImplementation
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.jvm.toolchain.internal.DefaultToolchainSpec
import org.gradle.jvm.toolchain.internal.SpecificInstallationToolchainSpec
import org.gradle.kotlin.dsl.support.serviceOf
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths


class TestJvmSpec(val project: Project) {
  companion object {
    const val TEST_JVM = "testJvm"
  }

  private val currentJavaHomePath = project.providers.systemProperty("java.home").map { it.normalizeToJDKJavaHome() }

  /**
   * The raw `testJvm` property as passed via command line or environment variable.
   */
  val testJvmProperty: Provider<String> = project.providers.gradleProperty(TEST_JVM)

  /**
   * Normalized `stable` string to the highest JAVA_X_HOME found in environment variables.
   */
  val normalizedTestJvm: Provider<String> = testJvmProperty.map { testJvm ->
    if (testJvm.isBlank()) {
      throw GradleException("testJvm property is blank")
    }

    // "stable" is calculated as the largest X found in JAVA_X_HOME
    if (testJvm == "stable") {
      val javaVersions = project.providers.environmentVariablesPrefixedBy("JAVA_").map { javaHomes ->
        javaHomes
          .filter { it.key.matches(Regex("^JAVA_[0-9]+_HOME$")) }
          .map { Regex("^JAVA_(\\d+)_HOME$").find(it.key)!!.groupValues[1].toInt() }
      }.get()

      if (javaVersions.isEmpty()) {
        throw GradleException("No valid JAVA_X_HOME environment variables found.")
      }

      javaVersions.max().toString()
    } else {
      testJvm
    }
  }.map { project.logger.info("normalized testJvm: $it"); it }

  /**
   * The home path of the test JVM.
   *
   * The `<testJvm>` string (`8`, `11`, `ZULU8`, `GRAALVM25`, etc.) is interpreted in that order:
   * 1. Lookup for a valid path,
   * 2. Look for an environment variable named `JAVA_<testJvm>_HOME`
   *    (e.g. `JAVA_8_HOME`, `JAVA_11_HOME`, etc. but also `JAVA_ZULU8_HOME`, `JAVA_GRAALVM25_HOME`, etc.)
   * 3. Look for an environment variable named `<testJvm>`
   * 4. Look JVM via Gradle toolchains
   *
   * Known forms
   */
  private val testJvmSpec = normalizedTestJvm.map {
    // https://github.com/DataDog/dd-trace-java-docker-build/blob/a4f4bfa9d7fe0708858e595697dc67970a2a458f/Dockerfile#L182-L188
    // https://github.com/DataDog/dd-trace-java-docker-build/blob/a4f4bfa9d7fe0708858e595697dc67970a2a458f/Dockerfile#L222-L241
    val testJvmSpec = when {
      Files.exists(Paths.get(it)) -> it.logSource("existing path")
      Regex("([a-zA-Z]*)([0-9]+)").find(it) != null ->
        project.providers.environmentVariable("JAVA_${it}_HOME").orNull
          ?.logSource("env var JAVA_${it}_HOME")

      project.providers.environmentVariable(it).isPresent ->
        project.providers.environmentVariable(it).orNull
          ?.logSource("env var $it")
      else -> null
    }?.normalizeToJDKJavaHome()?.toToolchainSpec() ?: run {
      // Best effort to make a spec for the passed testJvm
      // `8`, `11`, `ZULU8`, `GRAALVM25`, etc.
      // if it is an integer, we assume it's a Java version
      // also we can handle on macOs oracle, zulu, semeru, graalvm prefixes

      val (distribution, version) = Regex("([a-zA-Z]*)([0-9]+)").matchEntire(it)?.groupValues?.drop(1) ?: listOf("", "")
      version.ifBlank {
        return@run null
      }

      // This is using internal APIs
      DefaultToolchainSpec(project.serviceOf<PropertyFactory>()).apply {
        languageVersion.set(JavaLanguageVersion.of(version.toInt()))
        when (distribution.lowercase()) {
          "oracle" -> {
            vendor.set(JvmVendorSpec.ORACLE)
          }
          "zulu" -> {
            vendor.set(JvmVendorSpec.AZUL)
          }
          "semeru" -> {
            vendor.set(JvmVendorSpec.IBM)
            implementation.set(JvmImplementation.J9)
          }
          "graalvm" -> {
            vendor.set(JvmVendorSpec.GRAAL_VM)
            nativeImageCapable.set(true)
          }
        }
      }
    }

    if (testJvmSpec == null) {
      throw GradleException(
        """
        Unable to find launcher for Java '$it'. It needs to be:
        1. A valid path to a JDK home, or
        2. An environment variable named 'JAVA_<testJvm>_HOME' or '<testJvm>' pointing to a JDK home, or
        3. A Java version or a known distribution+version combination (e.g. '11', 'zulu8', 'graalvm11', etc.) that can be resolved via Gradle toolchains.
        4. If using Gradle toolchains, ensure that the requested JDK is installed and configured correctly.
        """.trimIndent())
    }

    // The test JVM spec for the toolchain service
    testJvmSpec
  }.map { project.logger.info("testJvm home path: $it"); it }

  /**
   * The Java launcher for the test JVM.
   *
   * Current JVM or a launcher specified via the testJvm.
   */
  val javaTestLauncher: Provider<JavaLauncher> = project.providers.zip(testJvmSpec, normalizedTestJvm) { jvmSpec, testJvm ->
    // Only change test JVM if it's not the one we are running the gradle build with
    if ((jvmSpec as? SpecificInstallationToolchainSpec)?.javaHome == currentJavaHomePath.get()) {
      project.providers.provider<JavaLauncher?> { null }
    } else {
      // The provider always says that a value is present so we need to wrap it for proper error messages
      project.javaToolchains.launcherFor(jvmSpec).orElse(project.providers.provider {
        throw GradleException("Unable to find launcher for Java '$testJvm'. Does $TEST_JVM point to a JDK?")
      })
    }
  }.flatMap { it }.map { project.logger.info("testJvm launcher: ${it.executablePath}"); it }

  private fun String.normalizeToJDKJavaHome(): Path {
    val javaHome = project.file(this).toPath().toRealPath()
    return if (javaHome.endsWith("jre")) javaHome.parent else javaHome
  }

  private fun String.logSource(resolutionMode: String): String {
    project.logger.info("$TEST_JVM=$this resolved from $resolutionMode")
    return this
  }

  private fun Path.toToolchainSpec() : JavaToolchainSpec =
    // This is using internal APIs
    SpecificInstallationToolchainSpec(project.serviceOf<PropertyFactory>(), project.file(this))

  private val Project.javaToolchains: JavaToolchainService
    get() =
      extensions.getByName("javaToolchains") as JavaToolchainService
}
