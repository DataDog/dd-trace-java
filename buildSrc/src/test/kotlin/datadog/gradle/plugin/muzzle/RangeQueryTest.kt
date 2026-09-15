package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.MavenRepoFixture
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.VersionRangeRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RangeQueryTest {
  @TempDir
  lateinit var tempDir: File

  private val system = MuzzleMavenRepoUtils.newRepositorySystem()
  private val session = MuzzleMavenRepoUtils.newRepositorySystemSession(system)

  @Test
  fun `test range request`() {
    val repository = MavenRepoFixture(tempDir)
    repository.publishVersions(
      "org.codehaus.groovy",
      "groovy-all",
      (0..8).map { "2.5.$it" },
    )
    val directiveArtifact: Artifact =
      DefaultArtifact("org.codehaus.groovy", "groovy-all", "jar", "[2.5.0,2.5.8)")
    val rangeRequest = VersionRangeRequest().apply {
      repositories =
        listOf(RemoteRepository.Builder("fixture", "default", repository.repoUrl).build())
      artifact = directiveArtifact
    }

    val rangeResult = system.resolveVersionRange(session, rangeRequest)

    assertThat(rangeResult.versions.map { it.toString() })
      .containsExactlyElementsOf((0..7).map { "2.5.$it" })
  }
}
