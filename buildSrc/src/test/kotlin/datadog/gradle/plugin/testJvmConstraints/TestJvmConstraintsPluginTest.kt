package datadog.gradle.plugin.testJvmConstraints

import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension.Companion.TEST_JVM_CONSTRAINTS
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.JavaVersion
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test as GradleTest
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.junit.jupiter.api.Test
import java.nio.file.Paths

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
  fun `plugin is idempotent when applied more than once`() {
    val project = ProjectBuilder.builder().build()

    project.pluginManager.apply("dd-trace-java.test-jvm-constraints")
    TestJvmConstraintsPlugin().apply(project)

    val testTask = project.tasks.named("test", GradleTest::class.java).get()

    assertThat(project.extensions.findByName(TEST_JVM_CONSTRAINTS)).isInstanceOf(TestJvmConstraintsExtension::class.java)
    assertThat(testTask.extensions.findByName(TEST_JVM_CONSTRAINTS)).isInstanceOf(TestJvmConstraintsExtension::class.java)
  }

  @Test
  fun `project toolchain is preserved and fingerprinted without testJvm`() {
    val project = ProjectBuilder.builder().build()
    project.pluginManager.apply("dd-trace-java.test-jvm-constraints")
    project.extensions.getByType(JavaPluginExtension::class.java).toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    val testTask = project.tasks.named("test", GradleTest::class.java).get()
    val metadata = testTask.javaLauncher.get().metadata

    assertThat(metadata.languageVersion).isEqualTo(JavaLanguageVersion.of(17))
    assertThat(testTask.inputs.properties["jvmIdentity"]).isEqualTo(mapOf(
      "languageVersion" to metadata.languageVersion.asInt().toString(),
      "vendor" to metadata.vendor,
      "runtimeVersion" to metadata.javaRuntimeVersion,
      "vmVersion" to metadata.jvmVersion,
      "operatingSystem" to System.getProperty("os.name"),
      "architecture" to System.getProperty("os.arch"),
    ))
  }

  @Test
  fun `current JVM version selected by testJvm overrides the project toolchain`() {
    val javaHome = System.getProperty("java.home")
    withTestJvm(JavaVersion.current().majorVersion) {
      val project = ProjectBuilder.builder().build()
      project.pluginManager.apply("dd-trace-java.test-jvm-constraints")
      project.extensions.getByType(JavaPluginExtension::class.java).toolchain.languageVersion.set(JavaLanguageVersion.of(17))

      val testTask = project.tasks.named("test", GradleTest::class.java).get()

      assertThat(testTask.javaLauncher.get().metadata.installationPath.asFile.toPath().toRealPath())
        .isEqualTo(Paths.get(javaHome).toRealPath())
    }
  }

  @Test
  fun `current JVM home selected by testJvm does not require a launcher override`() {
    withTestJvm(System.getProperty("java.home")) {
      val project = ProjectBuilder.builder().build()
      project.pluginManager.apply("java")
      val testJvmSpec = TestJvmSpec(project)

      assertThat(testJvmSpec.requestedTestJvmLauncher.get().metadata.isCurrentJvm).isTrue()
      assertThat(testJvmSpec.javaTestLauncherOverride.isPresent).isFalse()
    }
  }

  @Test
  fun `current JVM version selected by testJvm does not require a launcher override`() {
    withTestJvm(JavaVersion.current().majorVersion) {
      val project = ProjectBuilder.builder().build()
      project.pluginManager.apply("java")
      val testJvmSpec = TestJvmSpec(project)

      assertThat(testJvmSpec.requestedTestJvmLauncher.get().metadata.isCurrentJvm).isTrue()
      assertThat(testJvmSpec.javaTestLauncherOverride.isPresent).isFalse()
    }
  }

  @Test
  fun `matching current JVM still applies testJvm exclusion without a launcher override`() {
    val testJvm = JavaVersion.current().majorVersion
    withTestJvm(testJvm) {
      val project = ProjectBuilder.builder().build()
      project.pluginManager.apply("dd-trace-java.test-jvm-constraints")
      project.extensions.getByType(TestJvmConstraintsExtension::class.java).excludeJdk.add(testJvm)
      project.pluginManager.apply("jacoco")

      val testTask = project.tasks.named("test", GradleTest::class.java).get()

      assertThat(testTask.onlyIf.isSatisfiedBy(testTask)).isFalse()
      assertThat(jacocoExtension(testTask).isEnabled).isTrue()
    }
  }

  @Test
  fun `matching current JVM still applies forceJdk without a launcher override`() {
    val testJvm = JavaVersion.current().majorVersion
    withTestJvm(testJvm) {
      val project = ProjectBuilder.builder().build()
      project.pluginManager.apply("dd-trace-java.test-jvm-constraints")
      project.extensions.getByType(TestJvmConstraintsExtension::class.java).run {
        maxJavaVersion.set(JavaVersion.toVersion(testJvm.toInt() - 1))
        forceJdk.add(testJvm)
      }

      val testTask = project.tasks.named("test", GradleTest::class.java).get()

      assertThat(testTask.onlyIf.isSatisfiedBy(testTask)).isTrue()
    }
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

  private fun withTestJvm(value: String, assertions: () -> Unit) {
    val propertyName = "org.gradle.project.${TestJvmSpec.TEST_JVM}"
    val previousValue = System.setProperty(propertyName, value)
    try {
      assertions()
    } finally {
      if (previousValue == null) {
        System.clearProperty(propertyName)
      } else {
        System.setProperty(propertyName, previousValue)
      }
    }
  }
}
