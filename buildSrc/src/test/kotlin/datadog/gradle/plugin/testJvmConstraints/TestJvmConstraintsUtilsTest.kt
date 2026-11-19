package datadog.gradle.plugin.testJvmConstraints

import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.JavaVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class TestJvmConstraintsUtilsTest {
  @ParameterizedTest(name = "{0}")
  @CsvSource(
    delimiter = '|',
    nullValues = ["-"],
    value = [
      "no constraint                    | 8       | 8  | -  | -  | -       | -      | -       | true",
      "below minimum                    | 8       | 8  | 11 | -  | -       | -      | -       | false",
      "above maximum                    | 17      | 17 | -  | 8  | -       | -      | -       | false",
      "within range                     | 11      | 11 | 8  | 17 | -       | -      | -       | true",
      "out of range but forced          | zulu8   | 8  | 11 | -  | -       | -      | ZULU8   | true",
      "not included                     | 8       | 8  | -  | -  | ORACLE8 | -      | -       | false",
      "included                         | oracle8 | 8  | -  | -  | ORACLE8 | -      | -       | true",
      "included but below minimum       | oracle8 | 8  | 11 | -  | ORACLE8 | -      | -       | false",
      "excluded                         | ibm8    | 8  | -  | -  | -       | IBM8   | -       | false",
      "excluded even when forced        | ibm8    | 8  | 11 | -  | -       | IBM8   | IBM8    | false",
    ]
  )
  fun `test JVM eligibility`(
    scenario: String,
    testJvm: String,
    version: String,
    min: String?,
    max: String?,
    include: String?,
    exclude: String?,
    force: String?,
    expected: Boolean,
  ) {
    val allowed = isTestJvmAllowed(
      testJvm,
      { JavaVersion.toVersion(version) },
      listOfNotNull(include),
      listOfNotNull(exclude),
      listOfNotNull(force),
      min?.let(JavaVersion::toVersion),
      max?.let(JavaVersion::toVersion),
    )

    assertThat(allowed).isEqualTo(expected)
  }

  @Test
  fun `version is not computed when the JVM is not included`() {
    val allowed = isTestJvmAllowed(
      "8",
      { error("must not be computed") },
      listOf("ORACLE8"),
      emptyList(),
      emptyList(),
      null,
      null,
    )

    assertThat(allowed).isFalse()
  }
}
