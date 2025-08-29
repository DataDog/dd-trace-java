package datadog.gradle.plugin.muzzle

import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.resolution.VersionRangeRequest
import org.eclipse.aether.resolution.VersionRangeResult
import spock.lang.Specification

class RangeQueryTest extends Specification {

  RepositorySystem system = MuzzleMavenRepoUtils.newRepositorySystem()
  RepositorySystemSession session = MuzzleMavenRepoUtils.newRepositorySystemSession(system)

  def "test range request"() {
    setup:
//    compile group: 'org.codehaus.groovy', name: 'groovy-all', version: '2.5.0', ext: 'pom'
    final Artifact directiveArtifact = new DefaultArtifact("org.codehaus.groovy", "groovy-all", "jar", "[2.5.0,2.5.8)")
    final VersionRangeRequest rangeRequest = new VersionRangeRequest()
    rangeRequest.setRepositories(MuzzleMavenRepoUtils.MUZZLE_REPOS)
    rangeRequest.setArtifact(directiveArtifact)

    // This call makes an actual network request, which may fail if network access is limited.
    final VersionRangeResult rangeResult = system.resolveVersionRange(session, rangeRequest)

    expect:
    rangeResult.versions.size() >= 8
  }
}
