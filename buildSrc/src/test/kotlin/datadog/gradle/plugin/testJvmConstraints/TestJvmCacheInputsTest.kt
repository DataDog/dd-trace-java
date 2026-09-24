package datadog.gradle.plugin.testJvmConstraints

import datadog.gradle.plugin.GradleFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.FROM_CACHE
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TestJvmCacheInputsTest : GradleFixture() {
  @ParameterizedTest
  @ValueSource(strings = ["vendor", "runtimeVersion", "vmVersion"])
  fun `test cache follows the selected launcher identity`(changedField: String) {
    writeSettings(
      """
      rootProject.name = "test-jvm-inputs"
      buildCache { local { directory = file("task-cache") } }
      """
    )
    writeRootProject(
      """
      import org.gradle.jvm.toolchain.JavaInstallationMetadata
      import org.gradle.jvm.toolchain.JavaLauncher

      plugins {
        id("dd-trace-java.test-jvm-constraints")
      }

      repositories { mavenCentral() }
      dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:5.14.1")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
      }

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

      val launcher = javaToolchains.launcherFor {}
      val changedField = providers.gradleProperty("changedJvmField").orElse("")
      tasks.test {
        useJUnitPlatform()
        javaLauncher.set(launcher.map { SelectedLauncher(it, changedField.get()) })
      }
      """
    )
    writeJavaSource(
      "ExampleTest",
      """
      public class ExampleTest {
        @org.junit.jupiter.api.Test
        public void passes() {}
      }
      """,
      sourceSet = "test",
    )

    val first = run("test", "--build-cache")
    assertThat(first.task(":test")?.outcome).describedAs(first.output).isEqualTo(SUCCESS)

    val unchanged = run("test", "--build-cache")
    assertThat(unchanged.task(":test")?.outcome).describedAs(unchanged.output).isEqualTo(UP_TO_DATE)

    val restored = run("cleanTest", "test", "--build-cache")
    assertThat(restored.task(":test")?.outcome).describedAs(restored.output).isEqualTo(FROM_CACHE)

    val changed = run("test", "--build-cache", "-PchangedJvmField=$changedField")
    assertThat(changed.task(":test")?.outcome).describedAs(changed.output).isEqualTo(SUCCESS)
  }
}
