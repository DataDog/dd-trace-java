package datadog.trace.instrumentation.maven3

import datadog.trace.agent.test.SpockRunner
import org.junit.runner.RunWith
import spock.lang.Specification

@RunWith(SpockRunner)
class MavenDependencyVersionTest extends Specification {

  def "test Maven dependency version parsing: #a, #b"() {
    setup:
    def versionA = MavenDependencyVersion.from(a)
    def versionB = MavenDependencyVersion.from(b)

    expect:
    versionB.isLaterThanOrEqualTo(versionA)

    where:
    a | b
    "1" | "2"
    "1.0" | "2.0"
    "1.0.0" | "1.1.0"
    "1.0.0" | "1.0.1"
    "1.0.2" | "1.1.0"
    "1.0" | "1.0.1"
    "1.0.0-alpha-1" | "1.0.0"
    "1.0.0-alpha-1" | "1.0.0-alpha-2"
    "0.0.1" | "0.0.2-alpha-1"
    "0.0.1-alpha-1" | "0.0.2"
  }

  def "test UNKNOWN version"() {
    setup:
    def version = MavenDependencyVersion.from("0.0.0")

    expect:
    version.isLaterThanOrEqualTo(MavenDependencyVersion.UNKNOWN)
    !MavenDependencyVersion.UNKNOWN.isLaterThanOrEqualTo(version)
  }
}
