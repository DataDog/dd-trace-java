package datadog.gradle.plugin.muzzle

import org.eclipse.aether.repository.RemoteRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.assertj.core.api.Assertions.assertThat

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
}
