package datadog.gradle.plugin.testJvmConstraints

import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class CoverageTestJvmTest {
  @ParameterizedTest(name = "{0}")
  @CsvSource(
    delimiter = '|',
    nullValues = ["-"],
    value = [
      "no constraint                 | -  | -   | -       | -        | -     | -       | 8",
      "minimum Java 11               | 11 | -   | -       | -        | -     | -       | 11",
      "minimum non-LTS Java 12       | 12 | -   | -       | -        | -     | -       | 17",
      "minimum non-LTS Java 22       | 22 | -   | -       | -        | -     | -       | 25",
      "excluded lowest LTS           | -  | -   | -       | 8        | -     | -       | 11",
      "forced vendor JVM is ignored  | 11 | -   | -       | SEMERU11 | ZULU8 | -       | 11",
      "only a vendor JVM included    | -  | 1.8 | ORACLE8 | -        | -     | -       | -",
      "coverage test JVM set         | -  | 1.8 | ORACLE8 | -        | -     | ORACLE8 | ORACLE8",
      "unsatisfiable range           | 11 | 1.8 | -       | -        | -     | -       | -",
    ]
  )
  fun `coverage test JVM is the lowest allowed LTS version unless set`(
    scenario: String,
    min: String?,
    max: String?,
    include: String?,
    exclude: String?,
    force: String?,
    coverage: String?,
    expected: String?,
  ) {
    val project = projectWithTestJvmConstraints()
    project.extensions.getByType<TestJvmConstraintsExtension>().apply {
      min?.let { minJavaVersion.set(JavaVersion.toVersion(it)) }
      max?.let { maxJavaVersion.set(JavaVersion.toVersion(it)) }
      includeJdk.set(listOfNotNull(include))
      excludeJdk.set(listOfNotNull(exclude))
      forceJdk.set(listOfNotNull(force))
      coverage?.let { coverageTestJvm.set(it) }
    }

    assertThat(project.coverageTestJvm()).isEqualTo(expected)
  }

  @ParameterizedTest
  @ValueSource(ints = [8, 11, 17, 21, 25, 29])
  fun `LTS versions are long-term support`(version: Int) {
    assertThat(isLongTermSupport(version)).isTrue()
  }

  @ParameterizedTest
  @ValueSource(ints = [7, 9, 10, 12, 16, 22, 26, 27])
  fun `other versions are not long-term support`(version: Int) {
    assertThat(isLongTermSupport(version)).isFalse()
  }

  @Test
  fun `native-image tests have no coverage test JVM`() {
    val project = projectWithTestJvmConstraints()
    project.extensions.getByType<TestJvmConstraintsExtension>().nativeImageCapable.set(true)

    assertThat(project.coverageTestJvm()).isNull()
  }

  @Test
  fun `project without test task has no coverage test JVM`() {
    val project = ProjectBuilder.builder().build()

    assertThat(project.coverageTestJvm()).isNull()
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
    delimiter = '|',
    nullValues = ["-"],
    value = [
      "no coverage requested          | false | false | 11     | false",
      "every test JVM                 | true  | false | 17     | true",
      "coverage test JVM matches      | false | true  | 11     | true",
      "vendor JVM of the same version | false | true  | zulu11 | false",
      "coverage test JVM differs      | false | true  | 8      | false",
      "no testJvm                     | false | true  | -      | true",
    ]
  )
  fun `coverage is checked on the requested test JVM`(
    scenario: String,
    checkCoverage: Boolean,
    checkCoverageOnFirstTestJvm: Boolean,
    testJvm: String?,
    expected: Boolean,
  ) {
    val project = projectWithTestJvmConstraints()
    project.extensions.getByType<TestJvmConstraintsExtension>().minJavaVersion.set(JavaVersion.VERSION_11)

    assertThat(project.checksCoverage(checkCoverage, checkCoverageOnFirstTestJvm, testJvm)).isEqualTo(expected)
  }

  @Test
  fun `coverage test JVM set is matched ignoring case`() {
    val project = projectWithTestJvmConstraints()
    project.extensions.getByType<TestJvmConstraintsExtension>().apply {
      maxJavaVersion.set(JavaVersion.VERSION_1_8)
      includeJdk.set(listOf("ORACLE8"))
      coverageTestJvm.set("ORACLE8")
    }

    assertThat(project.checksCoverage(false, true, "oracle8")).isTrue()
  }

  private fun projectWithTestJvmConstraints(): Project {
    val project = ProjectBuilder.builder().build()
    project.pluginManager.apply("dd-trace-java.test-jvm-constraints")
    return project
  }
}
