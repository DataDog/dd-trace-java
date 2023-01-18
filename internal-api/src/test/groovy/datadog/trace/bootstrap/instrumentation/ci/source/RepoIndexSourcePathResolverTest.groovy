package datadog.trace.bootstrap.instrumentation.ci.source

import spock.lang.Specification

class RepoIndexSourcePathResolverTest extends Specification {

  // FIXME update
  def "test source path resolution"() {
    setup:
    def sourcePathResolver = new RepoIndexSourcePathResolver("/repo/root")

    when:
    def path = sourcePathResolver.getSourcePath(ARandomClass)

    then:
    path == null
  }

  private static final class ARandomClass {}

}
