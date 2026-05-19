package datadog.buildlogic.smoketest

import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.plugins.JavaPlugin
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
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

    assertThat(project.extensions.findByType<SmokeTestAppExtension>()).isNotNull
  }

  @Test
  fun `plugin is a no-op when smokeTestApp_application is never called`() {
    val project = ProjectBuilder.builder().build()

    project.plugins.apply("dd-trace-java.smoke-test-app")

    // No task of our type should be registered until `application { }` is invoked.
    assertThat(project.tasks.withType<NestedGradleBuild>()).isEmpty()
  }

  @Test
  fun `extension defaults applicationDir to projectDir slash application`() {
    val project = ProjectBuilder.builder().build()
    project.plugins.apply("dd-trace-java.smoke-test-app")

    val extension = project.extensions.getByType<SmokeTestAppExtension>()

    assertThat(extension.applicationDir.get().asFile)
      .isEqualTo(project.layout.projectDirectory.dir("application").asFile)
  }

  @Test
  fun `extension defaults applicationBuildDir to buildDir slash application`() {
    val project = ProjectBuilder.builder().build()
    project.plugins.apply("dd-trace-java.smoke-test-app")

    val extension = project.extensions.getByType<SmokeTestAppExtension>()

    assertThat(extension.applicationBuildDir.get().asFile)
      .isEqualTo(project.layout.buildDirectory.dir("application").get().asFile)
  }

  @Test
  fun `extension defaults gradleVersion to the smoke-test pinned version`() {
    val project = ProjectBuilder.builder().build()
    project.plugins.apply("dd-trace-java.smoke-test-app")

    val extension = project.extensions.getByType<SmokeTestAppExtension>()

    assertThat(extension.gradleVersion.get()).isEqualTo(DEFAULT_NESTED_GRADLE_VERSION)
  }

  @Test
  fun `extension defaults javaLauncher to a JDK 21 toolchain`() {
    // JavaToolchainService is contributed by the `java-base` plugin; apply something that
    // pulls it in so ProjectBuilder can resolve the convention.
    val project = ProjectBuilder.builder().build()
    project.apply<JavaPlugin>()
    project.plugins.apply("dd-trace-java.smoke-test-app")

    val extension = project.extensions.getByType<SmokeTestAppExtension>()

    assertThat(extension.javaLauncher.get().metadata.languageVersion)
      .isEqualTo(JavaLanguageVersion.of(DEFAULT_NESTED_JAVA_VERSION))
  }
}
