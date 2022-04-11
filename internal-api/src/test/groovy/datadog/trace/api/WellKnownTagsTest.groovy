package datadog.trace.api

import datadog.trace.test.util.DDSpecification

class WellKnownTagsTest extends DDSpecification {

  def "well known tags doesn't modify its inputs"() {
    given:
    WellKnownTags wellKnownTags =
      new WellKnownTags("runtimeid", "hostname", "env", "service", "version","language")
    expect:
    wellKnownTags.getRuntimeId() as String == "runtimeid"
    wellKnownTags.getHostname() as String == "hostname"
    wellKnownTags.getEnv() as String == "env"
    wellKnownTags.getService() as String == "service"
    wellKnownTags.getVersion() as String == "version"
    wellKnownTags.getLanguage() as String == "language"
  }
}
