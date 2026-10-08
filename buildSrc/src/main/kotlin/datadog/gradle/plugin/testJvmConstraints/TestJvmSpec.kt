package datadog.gradle.plugin.testJvmConstraints

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.provider.PropertyFactory
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.internal.jvm.inspection.JavaInstallationRegistry
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmImplementation
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.jvm.toolchain.internal.SpecificInstallationToolchainSpec
import org.gradle.kotlin.dsl.support.serviceOf
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Handles the `testJvm` property to resolve a Java launcher for testing.
 *
 * The `testJvm` property can be set via command line or environment variable to specify
 * which JVM to use for running tests. E.g.
 *
 * ```shell
 * ./gradlew test -PtestJvm=ZULU11
 * ```
 *
 * This handles local setup, and CI environment, where the environment variables are defined here:
 * * https://github.com/DataDog/dd-trace-java-docker-build/blob/a4f4bfa9d7fe0708858e595697dc67970a2a458f/Dockerfile#L182-L188
 * * https://github.com/DataDog/dd-trace-java-docker-build/blob/a4f4bfa9d7fe0708858e595697dc67970a2a458f/Dockerfile#L222-L241
 */
class TestJvmSpec(val project: Project) {
  companion object {
    const val TEST_JVM = "testJvm"

    // JDK 27 TODO: remove after GA (tip will move to 27)
    internal fun resolveTipJavaVersion(javaVersions: List<Int>): String {
      val tipJavaVersions = javaVersions.filterNot { it == 27 }
      if (tipJavaVersions.isEmpty()) {
        throw GradleException("No Java installations found for tip after excluding Java 27.")
      }

      return tipJavaVersions.max().toString()
    }
  }

  /**
   * The raw `testJvm` property as passed via command line or environment variable.
   */
  val testJvmProperty: Provider<String> = project.providers.gradleProperty(TEST_JVM)

  /**
   * Normalized `tip` string that's set to the highest JAVA_X_HOME version found in environment variables.
   */
  val normalizedTestJvm: Provider<String> = testJvmProperty.map { testJvm ->
    if (testJvm.isBlank()) {
      throw GradleException("testJvm property is blank")
    }

    when (testJvm) {
      "tip" -> {
        val javaVersions = discoverJavaVersionsViaToolchains()
          ?: discoverJavaVersionsViaEnvVars()

        if (javaVersions.isEmpty()) {
          throw GradleException("No Java installations found via toolchains or JAVA_X_HOME environment variables.")
        }

        resolveTipJavaVersion(javaVersions) // JDK 27 TODO: revert back to "javaVersions.max().toString()" after GA
      }

      else -> testJvm
    }
  }.map { project.logger.info("normalized testJvm: {}", it); it }

  private val defaultJavaLauncher = project.javaToolchains.launcherFor(
    project.extensions.getByType(JavaPluginExtension::class.java).toolchain
  )

  /**
   * The launcher for the requested test JVM.
   *
   * The `<testJvm>` string (`8`, `11`, `ZULU8`, `GRAALVM25`, etc.) is interpreted in that order:
   * 1. Lookup for a valid path,
   * 2. Lookup via Gradle toolchains.
   */
  private val testJvmLauncher = normalizedTestJvm.flatMap { testJvm ->
    val (distribution, version) = Regex("([a-zA-Z]*)([0-9]+)").matchEntire(testJvm)?.groupValues?.drop(1) ?: listOf("", "")

    // Allow looking up JAVA_<TESTJVM>_HOME environment variable (e.g., JAVA_IBM8_HOME, JAVA_SEMERU8_HOME),
    // because gradle doesn't offer a way to distinguish ibm8 or semeru8
    val envVarValue = project.providers.environmentVariable("JAVA_${testJvm.uppercase()}_HOME").orNull

    val javaHome = when {
      Files.exists(Paths.get(testJvm)) -> testJvm.normalizeToJDKJavaHome()
      envVarValue != null && Files.exists(Paths.get(envVarValue)) -> envVarValue.normalizeToJDKJavaHome()
      else -> null
    }

    val launcher = when {
      javaHome != null -> javaHome.toLauncher()
      version.isNotBlank() -> {
        // Best effort to select a launcher for the passed testJvm
        // `8`, `11`, `ZULU8`, `GRAALVM25`, etc.
        // if it is an integer, we assume it's a Java version
        // also we can handle on macOs oracle, zulu, semeru, graalvm prefixes

        project.javaToolchains.launcherFor {
          languageVersion.set(JavaLanguageVersion.of(version.toInt()))
          when (distribution.lowercase()) {
            "" -> {
              // No-op
            }

            "oracle" -> {
              vendor.set(JvmVendorSpec.ORACLE)
            }

            "zulu" -> {
              vendor.set(JvmVendorSpec.AZUL)
            }

            // Note: Both IBM and Semeru report java.vendor = "IBM Corporation",
            // so JvmVendorSpec cannot distinguish them. Use JAVA_IBM8_HOME or
            // JAVA_SEMERU8_HOME env vars for reliable selection.
            "ibm", "semeru" -> {
              vendor.set(JvmVendorSpec.IBM)
              implementation.set(JvmImplementation.J9)
            }

            "graalvm" -> {
              vendor.set(JvmVendorSpec.GRAAL_VM)
              nativeImageCapable.set(true)
            }

            else -> {
              throw GradleException("Unknown JVM distribution '$distribution'. Supported: oracle, zulu, ibm, semeru, graalvm.")
            }
          }
        }
      }

      else -> throw GradleException(
        """
        Unable to find launcher for Java '$testJvm'. It needs to be:
        1. A valid path to a JDK home, or
        2. An environment variable named 'JAVA_<testJvm>_HOME' or '<testJvm>' pointing to a JDK home, or
        3. A Java version or a known distribution+version combination (e.g. '11', 'zulu8', 'graalvm11', etc.) that can be resolved via Gradle toolchains.
        4. If using Gradle toolchains, ensure that the requested JDK is installed and configured correctly.
        """.trimIndent()
      )
    }

    launcher.orElse(project.providers.provider {
      throw GradleException("Unable to find launcher for Java '$testJvm'. Does $TEST_JVM point to a JDK?")
    })
  }

  /**
   * The Java launcher for the test JVM.
   *
   * `requestedTestJvmLauncher` represents what `testJvm` selected. It is present whenever `testJvm` is set and
   * is used by the _test jvm constraints_, and external conventions like native-image checks, and JMH.
   *
   * Absent when no `testJvm` is supplied.
   */
  val requestedTestJvmLauncher: Provider<JavaLauncher> =
    testJvmLauncher.map { project.logger.info("testJvm launcher: {}", it.executablePath); it }

  /**
   * The launcher override required by test tasks.
   *
   * `javaTestLauncherOverride` represents whether `Test.javaLauncher` must be **explicitly** configured.
   * It is absent when `testJvm` and the project toolchain both select the Gradle daemon JVM,
   * because setting it would be redundant.
   *
   * Absent when the requested JVM and the default project launcher both use the current JVM.
   */
  internal val javaTestLauncherOverride: Provider<JavaLauncher> =
    requestedTestJvmLauncher.flatMap { requested ->
      if (!requested.metadata.isCurrentJvm) {
        project.providers.provider { requested }
      } else {
        defaultJavaLauncher.filter { !it.metadata.isCurrentJvm }.map { requested }
      }
    }

  private fun String.normalizeToJDKJavaHome(): Path {
    val javaHome = project.file(this).toPath().toRealPath()
    return if (javaHome.endsWith("jre")) javaHome.parent else javaHome
  }

  private fun Path.toLauncher(): Provider<JavaLauncher> =
    // No public API exists, use internal
    project.javaToolchains.launcherFor(
      SpecificInstallationToolchainSpec(project.serviceOf<PropertyFactory>(), project.file(this))
    )

  private val Project.javaToolchains: JavaToolchainService
    get() =
      extensions.getByName("javaToolchains") as JavaToolchainService

  /**
   * Discovers available Java versions via Gradle's internal JavaInstallationRegistry.
   */
  private fun discoverJavaVersionsViaToolchains(): List<Int>? {
    // No public API exists, use internal
    val registry = (project as ProjectInternal).services.get(JavaInstallationRegistry::class.java)
    val versions = registry.toolchains().mapNotNull { installation ->
        installation.metadata.languageVersion.majorVersion.toInt()
    }

    return if (versions.isNotEmpty()) {
      project.logger.info("Discovered Java versions via toolchains: {}", versions)
      versions
    } else {
      null
    }
  }

  /**
   * Discovers available Java versions via JAVA_X_HOME environment variables.
   * Fallback method when toolchain discovery is not available.
   */
  private fun discoverJavaVersionsViaEnvVars(): List<Int> {
    val versions = project.providers.environmentVariablesPrefixedBy("JAVA_").map { javaHomes ->
      javaHomes
        .filter { it.key.matches(Regex("^JAVA_[0-9]+_HOME$")) }
        .mapNotNull { Regex("^JAVA_(\\d+)_HOME$").find(it.key)?.groupValues?.get(1)?.toIntOrNull() }
    }.get()

    if (versions.isNotEmpty()) {
      project.logger.info("Discovered Java versions via JAVA_X_HOME env vars: {}", versions)
    }
    return versions
  }
}
