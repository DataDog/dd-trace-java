package datadog.trace.instrumentation.kotlin.coroutines

import datadog.trace.agent.test.InstrumentationSpecification

abstract class AbstractStandardSuspensionHandoffTest extends InstrumentationSpecification {
  def "standard #kind suspension preserves manual scope with cancellation #cancelled"() {
    setup:
    new StandardSuspensionHandoffTests().run(kind, cancelled, {
      assert TEST_WRITER.empty : "The finished trace must remain held until coroutine completion"
    } as Runnable)
    TEST_WRITER.waitForTraces(1)
    def trace = TEST_WRITER.get(0)
    def spans = trace.collectEntries { [(it.operationName.toString()): it] }

    expect:
    TEST_WRITER.size() == 1
    trace.size() == (cancelled ? 2 : 3)
    spans.manual.parentId == spans.parent.spanId
    cancelled || spans.child.parentId == spans.manual.spanId

    where:
    kind          | cancelled
    'cancellable' | false
    'cancellable' | true
    'safe'        | false
    'yield'       | false
    'delay'       | false
    'select'      | false
  }
}
