package datadog.trace.civisibility.utils

import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.civisibility.ipc.TestFramework
import datadog.trace.core.DDSpan
import spock.lang.Specification

class SpanUtilsTest extends Specification {
  def "test getFrameworks"() {
    when:
    def span = createSpanWithFrameworks(frameworkTag, frameworkVersionTag)
    def frameworks = SpanUtils.getFrameworks(span)

    then:
    frameworks == expected

    where:
    frameworkTag       | frameworkVersionTag      | expected
    "name"             | "version"                | [new TestFramework("name", "version")]
    "name"             | null                     | [new TestFramework("name", null)]
    null               | "version"                | []
    ["nameA", "nameB"] | ["versionA", "versionB"] | [new TestFramework("nameA", "versionA"), new TestFramework("nameB", "versionB")]
    ["nameA", "nameB"] | null                     | [new TestFramework("nameA", null), new TestFramework("nameB", null)]
    ["nameA", "nameB"] | ["versionA", null]       | [new TestFramework("nameA", "versionA"), new TestFramework("nameB", null)]
  }

  DDSpan createSpanWithFrameworks(Object frameworkTag, Object frameworkVersionTag) {
    def span = Stub(DDSpan)
    span.getTag(Tags.TEST_FRAMEWORK) >> frameworkTag
    span.getTag(Tags.TEST_FRAMEWORK_VERSION) >> frameworkVersionTag
    return span
  }
}
