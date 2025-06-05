package datadog.trace.civisibility.config

import spock.lang.Specification

class TracerEnvironmentTest extends Specification {
  def "test fallback to tag on no branch"() {
    setup:
    def builder = TracerEnvironment.builder()
    def environment = builder.branch(branch).tag(tag).build()

    expect:
    environment.branch == environmentBranch

    where:
    branch | tag       | environmentBranch
    "main" | "v.1.0.0" | "main"
    "main" | null      | "main"
    null   | "v.1.0.0" | "v.1.0.0"
    null   | null      | null
  }
}
