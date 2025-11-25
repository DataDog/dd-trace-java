package datadog.gradle.plugin.muzzle

import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.resolution.VersionRangeRequest
import org.gradle.internal.impldep.org.junit.Assert.assertTrue
import org.junit.jupiter.api.Test

class RangeQueryTest {
  private val system = MuzzleMavenRepoUtils.newRepositorySystem()
  private val session = MuzzleMavenRepoUtils.newRepositorySystemSession(system)

  @Test
  fun `test range request`() {
    // compile group: 'org.codehaus.groovy', name: 'groovy-all', version: '2.5.0', ext: 'pom'
    val directiveArtifact: Artifact = DefaultArtifact("org.codehaus.groovy", "groovy-all", "jar", "[2.5.0,2.5.8)")
    val rangeRequest = VersionRangeRequest().apply {
      repositories = MuzzleMavenRepoUtils.MUZZLE_REPOS
      artifact = directiveArtifact
    }

    // This call makes an actual network request, which may fail if network access is limited.
    val rangeResult = system.resolveVersionRange(session, rangeRequest)

    assertTrue(rangeResult.versions.size >= 8)
  }
}
