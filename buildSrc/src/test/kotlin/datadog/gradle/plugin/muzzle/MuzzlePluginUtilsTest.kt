package datadog.gradle.plugin.muzzle

import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.getByType
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.assertj.core.api.Assertions.assertThat

class MuzzlePluginUtilsTest {
  @Test
  fun `pathSlug for root project is empty`() {
    val root = ProjectBuilder.builder().withName("root").build()
    assertThat(root.pathSlug).isEmpty()
  }

  @ParameterizedTest(name = "[{index}] path ''{0}'' → slug ''{1}''")
  @CsvSource(
    value =
      [
        "foo,         foo",
        "foo_bar_baz, foo_bar_baz", // underscores are preserved (only colons are replaced)
      ])
  fun `pathSlug for single-level child project`(childName: String, expectedSlug: String) {
    val root = ProjectBuilder.builder().withName("root").build()
    val child = ProjectBuilder.builder().withParent(root).withName(childName.trim()).build()
    assertThat(child.pathSlug).isEqualTo(expectedSlug.trim())
  }

  @Test
  fun `pathSlug for deeply nested project replaces colons with underscores`() {
    val root = ProjectBuilder.builder().withName("root").build()
    val foo = ProjectBuilder.builder().withParent(root).withName("foo").build()
    val bar = ProjectBuilder.builder().withParent(foo).withName("bar").build()
    val baz = ProjectBuilder.builder().withParent(bar).withName("baz").build()

    assertThat(baz.pathSlug).isEqualTo("foo_bar_baz")
  }

  @Test
  fun `allMainSourceSet includes main and excludes test`() {
    val project = ProjectBuilder.builder().build()
    project.plugins.apply("java")

    val sourceSets = project.allMainSourceSet

    assertThat(sourceSets.map { it.name }).contains("main").doesNotContain("test")
  }

  @Test
  fun `allMainSourceSet includes all source sets whose name starts with main`() {
    val project = ProjectBuilder.builder().build()
    project.plugins.apply("java")
    project.extensions.getByType<SourceSetContainer>().apply {
      create("mainLegacy")
      create("mainJava8")
    }

    val sourceSets = project.allMainSourceSet

    assertThat(sourceSets.map { it.name }).containsExactlyInAnyOrder("main", "mainLegacy", "mainJava8")
  }
}
