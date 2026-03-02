package datadog.trace.core.tagprocessor

import static datadog.trace.bootstrap.instrumentation.api.Tags.VERSION

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.core.DDSpanContext
import datadog.trace.test.util.DDSpecification

class InternalTagsAdderTest extends DDSpecification {
  def "should add _dd.base_service when service differs to ddService"() {
    setup:
    def calculator = new InternalTagsAdder("test", null)
    def spanContext = Mock(DDSpanContext)

    when:
    def enrichedTags = calculator.processTags([:], spanContext, [])

    then:
    1 * spanContext.getServiceName() >> serviceName

    and:
    assert enrichedTags == expectedTags

    where:
    serviceName  | expectedTags
    "anotherOne" | ["_dd.base_service": UTF8BytesString.create("test")]
    "test"       | [:]
    "TeSt"       | [:]
  }

  def "should add version when DD_SERVICE = #serviceName and version = #ddVersion"() {
    setup:
    def calculator = new InternalTagsAdder("same", ddVersion)
    def spanContext = Mock(DDSpanContext)

    when:
    def enrichedTags = calculator.processTags(tags, spanContext, [])

    then:
    1 * spanContext.getServiceName() >> serviceName

    and:
    assert enrichedTags?.get(VERSION)?.toString() == expected


    where:
    serviceName | ddVersion | tags               | expected
    "same"      | null      | [:]                | null
    "different" | "1.0"     | [:]                | null
    "different" | "1.0"     | ["version": "2.0"] | "2.0"
    "same"      | null      | ["version": "2.0"] | "2.0"
    "same"      | "1.0"     | ["version": "2.0"] | "2.0"
    "same"      | "1.0"     | [:]                | "1.0"
  }
}
