package datadog.gradle.plugin.muzzle

import org.eclipse.aether.repository.RemoteRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException

class MuzzleDirectiveTest {

  @ParameterizedTest(name = "[{index}] nameSlug(''{0}'') == ''{1}''")
  @CsvSource(
    value =
      [
        "simple,          simple",
        "My Directive,    My-Directive",
        "foo/bar@baz#123, foo-bar-baz-123",
      ])
  fun `nameSlug replaces non-alphanumeric characters with dashes`(input: String, expected: String) {
    val directive = MuzzleDirective().apply { name = input }
    assertThat(directive.nameSlug).isEqualTo(expected.trim())
  }

  @Test
  fun `nameSlug returns empty string for empty name`() {
    val directive = MuzzleDirective().apply { name = "" }
    assertThat(directive.nameSlug).isEmpty()
  }

  @Test
  fun `nameSlug trims leading and trailing whitespace before replacing`() {
    val directive = MuzzleDirective().apply { name = "  spaces  " }
    assertThat(directive.nameSlug).isEqualTo("spaces")
  }

  @Test
  fun `nameSlug returns empty string when name is null`() {
    val directive = MuzzleDirective() // name defaults to null
    assertThat(directive.nameSlug).isEmpty()
  }

  @Test
  fun `getRepositories returns defaults unchanged when no additional repos`() {
    val directive = MuzzleDirective()
    val defaults = listOf(RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build())

    val repos = directive.getRepositories(defaults)

    // Same reference — no copy is made when additionalRepositories is empty
    assertThat(repos).isSameAs(defaults)
  }

  @Test
  fun `getRepositories appends additional repositories after defaults`() {
    val directive =
      MuzzleDirective().apply {
        extraRepository("myrepo", "https://example.com/repo")
        extraRepository("otherrepo", "https://other.example.com/repo", "default")
      }
    val defaults =
      listOf(
        RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build())

    val repos = directive.getRepositories(defaults)

    assertThat(repos.map { it.id }).containsExactly("central", "myrepo", "otherrepo")
  }

  @Test
  fun `coreJdk without version sets isCoreJdk true and javaVersion null`() {
    val directive = MuzzleDirective()
    directive.coreJdk()

    assertThat(directive.isCoreJdk).isTrue()
    assertThat(directive.javaVersion).isNull()
  }

  @Test
  fun `coreJdk with version sets isCoreJdk true and javaVersion`() {
    val directive = MuzzleDirective()
    directive.coreJdk("17")

    assertThat(directive.isCoreJdk).isTrue()
    assertThat(directive.javaVersion).isEqualTo("17")
  }

  @ParameterizedTest(name = "[{index}] coreJdk={0}, assertPass={1} → {2}")
  @CsvSource(
    value =
      [
        "true,  true,  Pass-core-jdk",
        "true,  false, Fail-core-jdk",
      ])
  fun `toString for coreJdk directive`(isCoreJdk: Boolean, assertPass: Boolean, expected: String) {
    val directive =
      MuzzleDirective().apply {
        if (isCoreJdk) coreJdk()
        this.assertPass = assertPass
      }
    assertThat(directive.toString()).isEqualTo(expected)
  }

  @ParameterizedTest(name = "[{index}] assertPass={0} → prefix ''{1}''")
  @CsvSource(
    value =
      [
        "true,  pass",
        "false, fail",
      ])
  fun `toString for non-coreJdk directive includes group module versions`(
    assertPass: Boolean,
    prefix: String
  ) {
    val directive =
      MuzzleDirective().apply {
        group = "com.example"
        module = "mylib"
        versions = "[1.0,2.0)"
        this.assertPass = assertPass
      }

    assertThat(directive.toString()).isEqualTo("$prefix com.example:mylib:[1.0,2.0)")
  }

  @Test
  fun `extraDependency accumulates multiple entries in order`() {
    val directive = MuzzleDirective()
    directive.extraDependency("com.example:dep1:1.0")
    directive.extraDependency("com.example:dep2:2.0")
    directive.extraDependency("com.example:dep3:3.0")

    assertThat(directive.additionalDependencies).containsExactly(
      "com.example:dep1:1.0",
      "com.example:dep2:2.0",
      "com.example:dep3:3.0"
    )
  }

  @Test
  fun `excludeDependency accumulates multiple entries in order`() {
    val directive = MuzzleDirective()
    directive.excludeDependency("com.example:excluded1")
    directive.excludeDependency("com.example:excluded2")

    assertThat(directive.excludedDependencies).containsExactly(
      "com.example:excluded1",
      "com.example:excluded2"
    )
  }

  @Test
  fun `extraRepository accumulates multiple entries in order`() {
    val directive = MuzzleDirective()
    directive.extraRepository("repo1", "https://repo1.example.com")
    directive.extraRepository("repo2", "https://repo2.example.com", "p2")

    assertThat(directive.additionalRepositories).containsExactly(
      Triple("repo1", "default", "https://repo1.example.com"),
      Triple("repo2", "p2", "https://repo2.example.com"),
    )
  }

  @Test
  fun `mavenPomOverrides groups matched versions by dependency`() {
    val directive = MuzzleDirective().apply {
      mavenPomOverrides {
        artifactVersions = "[7.4,8)"
        dependency("com.example") {
          matchVersions = mutableListOf("1.0", "2.0")
          replacement = "2.1"
        }
      }
    }

    assertThat(directive.mavenPomOverrideConfig?.artifactVersions).isEqualTo("[7.4,8)")
    assertThat(directive.mavenPomOverrideConfig?.dependencyVersionOverrides).containsExactlyEntriesOf(
      linkedMapOf("com.example" to linkedMapOf("1.0" to "2.1", "2.0" to "2.1"))
    )
  }

  @Test
  fun `mavenPomOverrides accepts Maven version ranges`() {
    val directive = MuzzleDirective().apply {
      mavenPomOverrides {
        artifactVersions = "[,)"
        dependency("com.example") {
          matchVersionRanges = mutableListOf("[1.0,2.0)")
          replacement = "2.1"
        }
      }
    }

    assertThat(directive.mavenPomOverrideConfig?.dependencyVersionRangeOverrides).containsExactlyEntriesOf(
      linkedMapOf(
        "com.example" to mutableListOf(MavenVersionRangeOverride("[1.0,2.0)", "2.1"))
      )
    )
    // An open range opts every version of the directive into Maven resolution.
    assertThat(directive.requiresMavenResolution("1.2.3")).isTrue()
  }

  @Test
  fun `mavenPomOverrides accepts a literal raw POM pattern`() {
    val directive = MuzzleDirective().apply {
      mavenPomOverrides {
        artifactVersions = "[7.4,8)"
        dependency("org.eclipse.jetty") {
          matchPattern = "\u0024{jetty.version}"
          replacement = "9.4.58.v20250814"
        }
      }
    }

    assertThat(directive.mavenPomOverrideConfig?.dependencyVersionOverrides).containsExactlyEntriesOf(
      linkedMapOf(
        "org.eclipse.jetty" to linkedMapOf(
          "\u0024{jetty.version}" to "9.4.58.v20250814"
        )
      )
    )
  }

  @Test
  fun `mavenPomOverrides requires an artifact version range`() {
    assertThatIllegalArgumentException().isThrownBy {
      MuzzleDirective().apply {
        mavenPomOverrides {
          dependency("com.example") {
            matchVersions = mutableListOf("1.0")
            replacement = "2.1"
          }
        }
      }
    }.withMessageContaining("artifactVersions is required in mavenPomOverrides")
  }

  @Test
  fun `mavenPomOverrides rejects an invalid artifact version range`() {
    assertThatIllegalArgumentException().isThrownBy {
      MuzzleDirective().apply {
        mavenPomOverrides {
          artifactVersions = "[7.4,8"
          dependency("com.example") {
            matchVersions = mutableListOf("1.0")
            replacement = "2.1"
          }
        }
      }
    }.withMessageContaining("Invalid artifactVersions Maven version range '[7.4,8'")
  }

  @Test
  fun `mavenPomOverrides requires at least one dependency override`() {
    assertThatIllegalArgumentException().isThrownBy {
      MuzzleDirective().apply {
        mavenPomOverrides { artifactVersions = "[7.4,8)" }
      }
    }.withMessageContaining("At least one dependency(group) { } override is required")
  }

  @Test
  fun `mavenPomOverrides rejects wildcard matches`() {
    assertThatIllegalArgumentException().isThrownBy {
      MuzzleDirective().apply {
        mavenPomOverrides {
          artifactVersions = "[7.4,8)"
          dependency("com.example") {
            matchVersions = mutableListOf("*")
            replacement = "2.1"
          }
        }
      }
    }.withMessageContaining("'*' is not supported in matchVersions")
  }

  @Test
  fun `mavenPomOverrides rejects invalid Maven version ranges`() {
    assertThatIllegalArgumentException().isThrownBy {
      MuzzleDirective().apply {
        mavenPomOverrides {
          artifactVersions = "[7.4,8)"
          dependency("com.example") {
            matchVersionRanges = mutableListOf("[1.0,2.0")
            replacement = "2.1"
          }
        }
      }
    }.withMessageContaining("Invalid matchVersionRanges entry '[1.0,2.0'")
  }

  @Test
  fun `mavenPomOverrides requires a dependency version selector`() {
    assertThatIllegalArgumentException().isThrownBy {
      MuzzleDirective().apply {
        mavenPomOverrides {
          artifactVersions = "[7.4,8)"
          dependency("com.example") { replacement = "2.1" }
        }
      }
    }.withMessageContaining(
      "At least one matchVersions, matchPattern, or matchVersionRanges entry is required"
    )
  }

  @Test
  fun `mavenPomOverrides requires a replacement`() {
    assertThatIllegalArgumentException().isThrownBy {
      MuzzleDirective().apply {
        mavenPomOverrides {
          artifactVersions = "[7.4,8)"
          dependency("com.example") { matchVersions = mutableListOf("1.0") }
        }
      }
    }.withMessageContaining("A replacement is required")
  }

  @Test
  fun `requiresMavenResolution respects configured artifact version range`() {
    val directive = MuzzleDirective().apply {
      mavenPomOverrides {
        artifactVersions = "[7.4,8)"
        dependency("com.example") {
          matchVersions = mutableListOf("1.0")
          replacement = "1.1"
        }
      }
    }

    assertThat(directive.requiresMavenResolution("7.4.14-ce")).isTrue()
    assertThat(directive.requiresMavenResolution("7.9.9-ccs")).isTrue()
    assertThat(directive.requiresMavenResolution("7.3.15-ce")).isFalse()
    assertThat(directive.requiresMavenResolution("8.0.0-ce")).isFalse()
  }
}
