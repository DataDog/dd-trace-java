package datadog.gradle.plugin.testJvmConstraints

import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension.Companion.TEST_JVM_CONSTRAINTS
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.tasks.testing.Test as GradleTest
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.junit.jupiter.api.Test

class TestJvmConstraintsPluginTest {
  @Test
  fun `plugin configures project and test task extensions`() {
    val project = ProjectBuilder.builder().build()

    project.pluginManager.apply("dd-trace-java.test-jvm-constraints")

    val testTask = project.tasks.named("test", GradleTest::class.java).get()

    assertThat(project.plugins.hasPlugin("java")).isTrue()
    assertThat(project.extensions.findByName(TEST_JVM_CONSTRAINTS)).isInstanceOf(TestJvmConstraintsExtension::class.java)
    assertThat(testTask.extensions.findByName(TEST_JVM_CONSTRAINTS)).isInstanceOf(TestJvmConstraintsExtension::class.java)
  }

  @Test
  fun `jacoco is disabled for additional test jvm when coverage is not checked`() {
    val testTask = testTaskWithJacoco()

    testTask.configureJacocoForAdditionalTestJvm(
      hasAdditionalTestJvmLauncher = true,
      checkCoverage = false
    )

    assertThat(jacocoExtension(testTask).isEnabled).isFalse()
  }

  @Test
  fun `jacoco remains enabled for additional test jvm when coverage is checked`() {
    val testTask = testTaskWithJacoco()

    testTask.configureJacocoForAdditionalTestJvm(
      hasAdditionalTestJvmLauncher = true,
      checkCoverage = true
    )

    assertThat(jacocoExtension(testTask).isEnabled).isTrue()
  }

  @Test
  fun `jacoco remains enabled when using the daemon jvm`() {
    val testTask = testTaskWithJacoco()

    testTask.configureJacocoForAdditionalTestJvm(
      hasAdditionalTestJvmLauncher = false,
      checkCoverage = false
    )

    assertThat(jacocoExtension(testTask).isEnabled).isTrue()
  }

  private fun testTaskWithJacoco(): GradleTest {
    val project = ProjectBuilder.builder().build()

    project.pluginManager.apply("java")
    project.pluginManager.apply("jacoco")

    return project.tasks.named("test", GradleTest::class.java).get()
  }

  private fun jacocoExtension(testTask: GradleTest): JacocoTaskExtension =
    testTask.extensions.getByType(JacocoTaskExtension::class.java)
}
