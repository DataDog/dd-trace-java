package datadog.gradle.plugin.testJvmConstraints

import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths


class TestJvmSpec(val project: Project) {
  companion object {
    const val TEST_JVM = "testJvm"
  }

  private val currentJavaHomePath = project.providers.systemProperty("java.home").map { it.normalizeToJDKJavaHome() }
  
  val testJvmProperty = project.providers.gradleProperty(TEST_JVM)

  val normalizedTestJvm = testJvmProperty.map { testJvm ->
    if (testJvm.isBlank()) {
      throw GradleException("testJvm property is blank")
    }

    // "stable" is calculated as the largest X found in JAVA_X_HOME
    if (testJvm == "stable") {
      val javaVersions = project.providers.environmentVariablesPrefixedBy("JAVA_").map { javaHomes ->
        javaHomes
          .filter { it.key.matches(Regex("^JAVA_[0-9]+_HOME$")) && it.key != "JAVA_26_HOME" } // JDK 26 is EA
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

  val testJvmHomePath = normalizedTestJvm.map {
    if (Files.exists(Paths.get(it))) {
      it.normalizeToJDKJavaHome()
    } else {
      val matcher = Regex("([a-zA-Z]*)([0-9]+)").find(it)
      if (matcher == null) {
        throw GradleException("Unable to find launcher for Java '$it'. It needs to match '([a-zA-Z]*)([0-9]+)'.")
      }
      val testJvmEnv = "JAVA_${it}_HOME"
      val testJvmHome = project.providers.environmentVariable(testJvmEnv).orNull
      if (testJvmHome == null) {
        throw GradleException("Unable to find launcher for Java '$it'. Have you set '$testJvmEnv'?")
      }

      testJvmHome.normalizeToJDKJavaHome()
    }
  }.map { project.logger.info("testJvm home path: $it"); it }

  val javaTestLauncher = project.providers.zip(testJvmHomePath, normalizedTestJvm) { testJvmHome, testJvm ->
    // Only change test JVM if it's not the one we are running the gradle build with
    if (currentJavaHomePath.get() == testJvmHome) {
      project.providers.provider<JavaLauncher?> { null }
    } else {
      // This is using internal APIs
      val jvmSpec = org.gradle.jvm.toolchain.internal.SpecificInstallationToolchainSpec(
        project.serviceOf<org.gradle.api.internal.provider.PropertyFactory>(),
        project.file(testJvmHome)
      )

      // The provider always says that a value is present so we need to wrap it for proper error messages
      project.javaToolchains.launcherFor(jvmSpec).orElse(project.providers.provider {
        throw GradleException("Unable to find launcher for Java $testJvm. Does '$testJvmHome' point to a JDK?")
      })
    }
  }.flatMap { it }.map { project.logger.info("testJvm launcher: ${it.executablePath}"); it }

  private fun String.normalizeToJDKJavaHome(): Path {
    val javaHome = project.file(this).toPath().toRealPath()
    return if (javaHome.endsWith("jre")) javaHome.parent else javaHome
  }

  private val Project.javaToolchains: JavaToolchainService get() =
    extensions.getByName("javaToolchains") as JavaToolchainService
}
