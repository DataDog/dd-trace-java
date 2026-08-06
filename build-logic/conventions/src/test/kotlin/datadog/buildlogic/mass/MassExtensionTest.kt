package datadog.buildlogic.mass

import org.assertj.core.api.Assertions.assertThat
import org.gradle.kotlin.dsl.newInstance
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

class MassExtensionTest {

  private fun extension(massReadUrl: String?): MassExtension {
    val project = ProjectBuilder.builder().build()
    // Empty overrides the environment-backed convention.
    return project.objects.newInstance<MassExtension>().also { it.readUrl.set(massReadUrl ?: "") }
  }

  @Test
  fun `routes artifacts through MASS and keeps the upstream host as a second repository`() {
    val mass = extension("https://mass.example")

    assertThat(mass.artifactUrls("dlcdn.apache.org")).containsExactly(
      "https://mass.example/internal/artifact/dlcdn.apache.org",
      "https://dlcdn.apache.org",
    )
    assertThat(mass.artifactUrl("dlcdn.apache.org"))
      .isEqualTo("https://mass.example/internal/artifact/dlcdn.apache.org")
  }

  @Test
  fun `tolerates a trailing slash on the MASS read url`() {
    val mass = extension("https://mass.example/")

    assertThat(mass.artifactUrls("dlcdn.apache.org").first())
      .isEqualTo("https://mass.example/internal/artifact/dlcdn.apache.org")
  }

  @Test
  fun `declares the upstream host only once when MASS is not configured`() {
    listOf(null, "", "   ").forEach { unset ->
      val mass = extension(unset)

      assertThat(mass.artifactUrls("dlcdn.apache.org"))
        .containsExactly("https://dlcdn.apache.org")
      assertThat(mass.artifactUrl("dlcdn.apache.org")).isEqualTo("https://dlcdn.apache.org")
    }
  }

  @Test
  fun `keeps any path on the upstream artifact url`() {
    val mass = extension("https://mass.example")

    assertThat(mass.artifactUrls("github.com/wildfly/wildfly/releases/download/")).containsExactly(
      "https://mass.example/internal/artifact/github.com/wildfly/wildfly/releases/download/",
      "https://github.com/wildfly/wildfly/releases/download/",
    )
  }
}
