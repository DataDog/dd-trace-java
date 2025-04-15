package datadog.trace.core.tagprocessor

import datadog.trace.api.ProcessTags
import datadog.trace.core.DDSpanContext
import datadog.trace.test.util.DDSpecification

class ProcessTagsAdderTest extends DDSpecification {
  def "should add _dd.process"() {
    setup:
    def adder = new ProcessTagsAdder()
    def spanContext = Mock(DDSpanContext)

    when:
    def enrichedTags = adder.processTags([:], spanContext, [])

    then:
    def firstValue = enrichedTags.get("_dd.process")
    assert !firstValue.toString().isEmpty()

    when:
    ProcessTags.addTag("test", "value")
    enrichedTags = adder.processTags([:], spanContext, [])

    then:
    assert enrichedTags.get("_dd.process").toString() == "$firstValue,test:value"
  }
}
