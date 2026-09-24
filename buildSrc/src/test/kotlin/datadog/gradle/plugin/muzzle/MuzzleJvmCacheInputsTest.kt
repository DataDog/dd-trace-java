package datadog.gradle.plugin.muzzle

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.FROM_CACHE
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import org.junit.jupiter.api.Test

class MuzzleJvmCacheInputsTest : MuzzlePluginTestFixture() {
  @Test
  fun `muzzle cache follows the daemon JVM when no toolchain is requested`() {
    writeProject(
      """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }

      muzzle { pass { coreJdk() } }

      // Simulate a changed daemon runtime without requiring several installed JDKs.
      val changedField = providers.gradleProperty("changedJvmField").orNull
      if (changedField != null) {
        val original = System.getProperty(changedField)
        System.setProperty(changedField, "different-runtime")
        gradle.buildFinished { System.setProperty(changedField, original) }
      }
      """
    )
    assertCacheTracks(listOf("java.vendor", "java.runtime.version", "java.vm.version"))
  }

  @Test
  fun `muzzle cache follows the launcher used by the isolated worker`() {
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

      val changedField = providers.gradleProperty("changedJvmField").orElse("")
      val launcher = javaToolchains.launcherFor {}
      tasks.withType<MuzzleTask>().configureEach {
        if (name == "muzzle-AssertPass-core-jdk") {
          javaLauncher.set(launcher.map { SelectedLauncher(it, changedField.get()) })
        }
      }
      """
    )
    assertCacheTracks(listOf("vendor", "runtimeVersion", "vmVersion"))
  }

  private fun assertCacheTracks(fields: List<String>) {
    writeFile("settings.gradle.kts", "buildCache { local { directory = file(\"task-cache\") } }", append = true)
    writeNoopScanPlugin()
    val task = ":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk"
    val first = run("muzzle", "--build-cache")
    assertThat(first.task(task)?.outcome).describedAs(first.output).isEqualTo(SUCCESS)

    val unchanged = run("muzzle", "--build-cache")
    assertThat(unchanged.task(task)?.outcome).describedAs(unchanged.output).isEqualTo(UP_TO_DATE)

    for (field in fields) {
      val restored = run("clean", "muzzle", "--build-cache")
      assertThat(restored.task(task)?.outcome).describedAs(restored.output).isEqualTo(FROM_CACHE)

      val changed = run("muzzle", "--build-cache", "-PchangedJvmField=$field")
      assertThat(changed.task(task)?.outcome).describedAs(changed.output).isEqualTo(SUCCESS)
    }
  }
}
