package datadog.gradle.plugin.testJvmConstraints

import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension.Companion.TEST_JVM_CONSTRAINTS
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.getByType
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import org.gradle.api.tasks.testing.Test as GradleTest

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
    assertThat(testTask.inputs.properties["jvmIdentity"]).isEqualTo(
      mapOf(
        "languageVersion" to metadata.languageVersion.asInt().toString(),
        "vendor" to metadata.vendor,
        "runtimeVersion" to metadata.javaRuntimeVersion,
        "vmVersion" to metadata.jvmVersion,
        "operatingSystem" to System.getProperty("os.name"),
        "architecture" to System.getProperty("os.arch"),
      )
    )
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
  fun `current JVM selected by testJvm overrides an unavailable project toolchain`() {
    val javaHome = System.getProperty("java.home")
    withTestJvm(JavaVersion.current().majorVersion) {
      val project = ProjectBuilder.builder().build()
      project.pluginManager.apply("dd-trace-java.test-jvm-constraints")
      project.extensions.getByType(JavaPluginExtension::class.java).toolchain.languageVersion.set(JavaLanguageVersion.of(99))

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
  fun `project includeJdk controls test task eligibility`() {
    val testJvm = JavaVersion.current().majorVersion
    withTestJvm(testJvm) {
      val project = ProjectBuilder.builder().build()
      project.pluginManager.apply("dd-trace-java.test-jvm-constraints")
      val projectExtension = project.extensions.getByType(TestJvmConstraintsExtension::class.java)
      projectExtension.includeJdk.set(listOf(testJvm))

      val testTask = project.tasks.named("test", GradleTest::class.java).get()

      assertThat(testTask.onlyIf.isSatisfiedBy(testTask)).isTrue()

      projectExtension.includeJdk.set(listOf("ORACLE8"))

      assertThat(testTask.onlyIf.isSatisfiedBy(testTask)).isFalse()
    }
  }

  @Test
  fun `task includeJdk overrides project inclusion`() {
    val testJvm = JavaVersion.current().majorVersion
    withTestJvm(testJvm) {
      val project = ProjectBuilder.builder().build()
      project.pluginManager.apply("dd-trace-java.test-jvm-constraints")
      project.extensions.getByType(TestJvmConstraintsExtension::class.java).includeJdk.set(listOf("ORACLE8"))
      val testTask = project.tasks.named("test", GradleTest::class.java).get()
      val taskExtension = testTask.extensions.getByType(TestJvmConstraintsExtension::class.java)

      taskExtension.includeJdk.set(listOf(testJvm))

      assertThat(testTask.onlyIf.isSatisfiedBy(testTask)).isTrue()

      taskExtension.includeJdk.set(listOf("ZULU8"))

      assertThat(testTask.onlyIf.isSatisfiedBy(testTask)).isFalse()

      taskExtension.includeJdk.set(emptyList())

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

  @Test
  fun `jacoco remains enabled on the coverage test JVM when coverage is checked on it`() {
    val current = JavaVersion.current()
    assumeTrue(isLongTermSupport(current.majorVersion.toInt()), "The current JVM must be an LTS version")
    withProjectProperties(mapOf(TestJvmSpec.TEST_JVM to current.majorVersion, CHECK_COVERAGE_ON_FIRST_TEST_JVM to "")) {
      val project = projectWithJacocoAndAnotherToolchain()
      // Realize the test task before the constraint is set, as a build script would
      val testTask = project.tasks.named("test", GradleTest::class.java).get()
      project.extensions.getByType<TestJvmConstraintsExtension>().minJavaVersion.set(current)

      (project as ProjectInternal).evaluate()

      assertThat(jacocoExtension(testTask).isEnabled).isTrue()
    }
  }

  @Test
  fun `jacoco is disabled on another test JVM when coverage is checked on the coverage test JVM`() {
    val current = JavaVersion.current()
    assumeTrue(current.majorVersion != "8", "The current JVM must not be the coverage test JVM")
    withProjectProperties(mapOf(TestJvmSpec.TEST_JVM to current.majorVersion, CHECK_COVERAGE_ON_FIRST_TEST_JVM to "")) {
      val project = projectWithJacocoAndAnotherToolchain()
      val testTask = project.tasks.named("test", GradleTest::class.java).get()

      (project as ProjectInternal).evaluate()

      assertThat(jacocoExtension(testTask).isEnabled).isFalse()
    }
  }

  /** Returns a project whose toolchain differs from the current JVM, so `testJvm` overrides the test launcher. */
  private fun projectWithJacocoAndAnotherToolchain(): Project {
    val project = ProjectBuilder.builder().build()
    project.pluginManager.apply("dd-trace-java.test-jvm-constraints")
    project.pluginManager.apply("jacoco")
    project.extensions.getByType(JavaPluginExtension::class.java).toolchain.languageVersion.set(JavaLanguageVersion.of(99))
    return project
  }

  private fun withProjectProperties(properties: Map<String, String>, assertions: () -> Unit) {
    val previousValues = properties.keys.associateWith { System.getProperty("org.gradle.project.$it") }
    properties.forEach { (name, value) -> System.setProperty("org.gradle.project.$name", value) }
    try {
      assertions()
    } finally {
      previousValues.forEach { (name, previous) ->
        if (previous == null) {
          System.clearProperty("org.gradle.project.$name")
        } else {
          System.setProperty("org.gradle.project.$name", previous)
        }
      }
    }
  }

  private fun testTaskWithJacoco(): GradleTest {
    val project = ProjectBuilder.builder().build()

    project.pluginManager.apply("java")
    project.pluginManager.apply("jacoco")

    return project.tasks.named("test", GradleTest::class.java).get()
  }

  private fun jacocoExtension(testTask: GradleTest): JacocoTaskExtension = testTask.extensions.getByType(JacocoTaskExtension::class.java)

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
