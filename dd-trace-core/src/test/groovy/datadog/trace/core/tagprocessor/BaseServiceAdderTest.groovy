package datadog.trace.core.tagprocessor


import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.core.DDSpanContext
import datadog.trace.test.util.DDSpecification

class BaseServiceAdderTest extends DDSpecification {
  def "should add _dd.base_service when service differs to ddService"() {
    setup:
    def calculator = new BaseServiceAdder("test")
    def spanContext = Mock(DDSpanContext)

    when:
    def enrichedTags = calculator.processTagsWithContext([:], spanContext)

    then:
    1 * spanContext.getServiceName() >> serviceName

    and:
    assert enrichedTags == expectedTags

    where:
    serviceName     | expectedTags
    "anotherOne"    | ["_dd.base_service": UTF8BytesString.create("test")]
    "test"          | [:]
    "TeSt"          | [:]
  }
}
