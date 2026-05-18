package datadog.buildlogic.smoketest

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

/**
 * Fast in-process tests that exercise plugin application and extension wiring through
 * [ProjectBuilder]. End-to-end task execution lives in [SmokeTestAppEndToEndTest].
 */
class SmokeTestAppPluginTest {

  @Test
  fun `applying the plugin creates the smokeTestApp extension`() {
    val project = ProjectBuilder.builder().build()

    project.plugins.apply("dd-trace-java.smoke-test-app")

    val extension = project.extensions.findByName("smokeTestApp")
    assertThat(extension).isInstanceOf(SmokeTestAppExtension::class.java)
  }

  @Test
  fun `plugin is a no-op when smokeTestApp_application is never called`() {
    val project = ProjectBuilder.builder().build()

    project.plugins.apply("dd-trace-java.smoke-test-app")

    // No task of our type should be registered until `application { }` is invoked.
    val nestedBuildTasks = project.tasks.withType(NestedGradleBuild::class.java)
    assertThat(nestedBuildTasks).isEmpty()
  }

  @Test
  fun `extension defaults applicationDir to projectDir slash application`() {
    val project = ProjectBuilder.builder().build()
    project.plugins.apply("dd-trace-java.smoke-test-app")

    val extension = project.extensions.getByType(SmokeTestAppExtension::class.java)

    assertThat(extension.applicationDir.get().asFile)
      .isEqualTo(project.layout.projectDirectory.dir("application").asFile)
  }

  @Test
  fun `extension defaults applicationBuildDir to buildDir slash application`() {
    val project = ProjectBuilder.builder().build()
    project.plugins.apply("dd-trace-java.smoke-test-app")

    val extension = project.extensions.getByType(SmokeTestAppExtension::class.java)

    assertThat(extension.applicationBuildDir.get().asFile)
      .isEqualTo(project.layout.buildDirectory.dir("application").get().asFile)
  }

  @Test
  fun `extension defaults gradleVersion to the host build's version`() {
    val project = ProjectBuilder.builder().build()
    project.plugins.apply("dd-trace-java.smoke-test-app")

    val extension = project.extensions.getByType(SmokeTestAppExtension::class.java)

    assertThat(extension.gradleVersion.get()).isEqualTo(project.gradle.gradleVersion)
  }
}
