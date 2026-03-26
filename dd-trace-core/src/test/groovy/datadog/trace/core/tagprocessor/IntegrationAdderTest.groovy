package datadog.trace.core.tagprocessor

import datadog.trace.api.TagMap
import datadog.trace.core.DDSpanContext
import datadog.trace.test.util.DDSpecification

class IntegrationAdderTest extends DDSpecification {
  def "should add or remove _dd.integration when set (#isSet) on the span context"() {
    setup:
    def calculator = new IntegrationAdder()
    def spanContext = Mock(DDSpanContext)

    when:
    def unsafeTags = TagMap.fromMap(["_dd.integration": "bad"])
	calculator.processTags(unsafeTags, spanContext, {link -> })

    then:
    1 * spanContext.getIntegrationName() >> (isSet ? "test" : null)

    and:
    assert unsafeTags == (isSet ? ["_dd.integration": "test"] : [:])

    where:
    isSet  << [true, false]
  }
}
