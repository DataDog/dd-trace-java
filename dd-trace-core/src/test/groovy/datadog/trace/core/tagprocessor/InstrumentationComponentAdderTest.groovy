package datadog.trace.core.tagprocessor


import datadog.trace.core.DDSpanContext
import datadog.trace.test.util.DDSpecification

class InstrumentationComponentAdderTest extends DDSpecification {
  def "should add or remove _dd.integration when set (#isSet) on the span context"() {
    setup:
    def calculator = new InstrumentationComponentAdder()
    def spanContext = Mock(DDSpanContext)

    when:

    def enrichedTags = calculator.processTags(["_dd.integration": "bad"], spanContext, [])

    then:
    1 * spanContext.getInstrumentationName() >> (isSet ? "test" : null)

    and:
    assert enrichedTags == (isSet ? ["_dd.integration": "test"] : [:])

    where:
    isSet  << [true, false]
  }
}
