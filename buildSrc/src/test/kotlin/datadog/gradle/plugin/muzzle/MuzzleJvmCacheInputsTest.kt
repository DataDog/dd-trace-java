package datadog.gradle.plugin.muzzle

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.TaskOutcome.FROM_CACHE
import org.gradle.testkit.runner.TaskOutcome.SKIPPED
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MuzzleJvmCacheInputsTest : MuzzlePluginTestFixture() {
  @ParameterizedTest
  @ValueSource(strings = ["coreJdk", "library", "fallback"])
  fun `only core JDK checks fingerprint the daemon JVM`(check: String) {
    val repo = createMavenRepoFixture()
    repo.publishVersions("com.example.test", "demo-lib", listOf("1.0.0"))
    val directive = when (check) {
      "coreJdk" -> "muzzle { pass { coreJdk() } }"
      "library" -> """
        muzzle { pass {
          group = "com.example.test"
          module = "demo-lib"
          versions = "[1.0.0,2.0.0)"
        } }
      """
      else -> ""
    }
    writeProject(
      """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }

      repositories { maven { url = uri("${repo.repoUrl}") } }

      $directive

      // Simulate a changed daemon runtime without requiring several installed JDKs.
      val changedField = providers.gradleProperty("changedJvmField").orNull
      if (changedField != null) {
        val original = System.getProperty(changedField)
        System.setProperty(changedField, "different-runtime")
        gradle.buildFinished { System.setProperty(changedField, original) }
      }
      """
    )
    val taskName = when (check) {
      "coreJdk" -> "muzzle-AssertPass-core-jdk"
      "library" -> "muzzle-AssertPass-com.example.test-demo-lib-1.0.0"
      else -> "muzzle"
    }
    assertCacheTracks(
      listOf("java.vendor", "java.runtime.version", "java.vm.version"),
      taskName,
      if (check == "coreJdk") SUCCESS else UP_TO_DATE,
      mapOf("MAVEN_REPOSITORY_PROXY" to repo.repoUrl),
    )
  }

  @Test
  fun `core JDK cache follows the launcher used by the isolated worker`() {
    writeProject(
      """
      import datadog.gradle.plugin.muzzle.tasks.MuzzleTask
      import org.gradle.jvm.toolchain.JavaInstallationMetadata
      import org.gradle.jvm.toolchain.JavaLauncher

      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }

      muzzle { pass { coreJdk(JavaVersion.current().majorVersion) } }

      // Keep the executable and major version fixed to isolate each additional input.
      class SelectedLauncher(
        private val delegate: JavaLauncher,
        private val changedField: String,
      ) : JavaLauncher by delegate {
        override fun getMetadata(): JavaInstallationMetadata =
          object : JavaInstallationMetadata by delegate.metadata {
            override fun getVendor() =
              if (changedField == "vendor") "different-vendor" else delegate.metadata.vendor
            override fun getJavaRuntimeVersion() =
              if (changedField == "runtimeVersion") "different-runtime" else delegate.metadata.javaRuntimeVersion
            override fun getJvmVersion() =
              if (changedField == "vmVersion") "different-vm" else delegate.metadata.jvmVersion
          }
      }

      val changedField = providers.gradleProperty("changedJvmField").orNull
      val launcher = javaToolchains.launcherFor {}
      tasks.withType<MuzzleTask>().configureEach {
        if (name == "muzzle-AssertPass-core-jdk" && changedField != null) {
          javaLauncher.set(launcher.map { SelectedLauncher(it, changedField) })
        }
      }
      """
    )
    assertCacheTracks(listOf("vendor", "runtimeVersion", "vmVersion"))
  }

  @ParameterizedTest
  @ValueSource(strings = ["--dry-run", "-PskipMuzzle"])
  fun `skipped core JDK checks do not resolve the toolchain`(argument: String) {
    writeProject(
      """
      import datadog.gradle.plugin.muzzle.tasks.MuzzleTask

      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }

      muzzle { pass { coreJdk("999") } }

      tasks.withType<MuzzleTask>().configureEach {
        onlyIf { !providers.gradleProperty("skipMuzzle").isPresent }
      }
      """
    )
    writeFile("gradle.properties", "org.gradle.java.installations.auto-download=false")
    val result = run("muzzle", argument)
    assertThat(result.output).contains("BUILD SUCCESSFUL")
    if (argument == "-PskipMuzzle") {
      assertThat(result.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome)
        .isEqualTo(SKIPPED)
    }
  }

  private fun assertCacheTracks(
    fields: List<String>,
    taskName: String = "muzzle-AssertPass-core-jdk",
    changedOutcome: TaskOutcome = SUCCESS,
    env: Map<String, String> = emptyMap(),
  ) {
    writeFile("settings.gradle.kts", "buildCache { local { directory = file(\"task-cache\") } }", append = true)
    writeNoopScanPlugin()
    val task = ":dd-java-agent:instrumentation:demo:$taskName"
    val first = run("muzzle", "--build-cache", env = env)
    assertThat(first.task(task)?.outcome).describedAs(first.output).isEqualTo(SUCCESS)

    val unchanged = run("muzzle", "--build-cache", env = env)
    assertThat(unchanged.task(task)?.outcome).describedAs(unchanged.output).isEqualTo(UP_TO_DATE)

    for (field in fields) {
      val restored = run("clean", "muzzle", "--build-cache", env = env)
      assertThat(restored.task(task)?.outcome).describedAs(restored.output).isEqualTo(FROM_CACHE)

      val changed = run("muzzle", "--build-cache", "-PchangedJvmField=$field", env = env)
      assertThat(changed.task(task)?.outcome).describedAs(changed.output).isEqualTo(changedOutcome)
    }
  }
}
