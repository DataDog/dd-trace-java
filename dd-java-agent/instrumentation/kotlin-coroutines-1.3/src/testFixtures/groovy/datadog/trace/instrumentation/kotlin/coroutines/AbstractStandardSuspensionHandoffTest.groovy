package datadog.trace.instrumentation.kotlin.coroutines

import datadog.trace.agent.test.InstrumentationSpecification

abstract class AbstractStandardSuspensionHandoffTest extends InstrumentationSpecification {
  def "early dispatcher #outcome preserves scope opened afterward #openAfter with delayed restore #delayedRestore"() {
    when:
    new StandardSuspensionHandoffTests().runEarlyDispatcherChange(openAfter, outcome, delayedRestore)
    TEST_WRITER.waitForTraces(1)
    def trace = TEST_WRITER.get(0)
    def spans = trace.collectEntries { [(it.operationName.toString()): it] }

    then:
    trace.size() == 2
    spans.child.parentId == spans.manual.spanId

    where:
    openAfter | outcome  | delayedRestore
    false     | 'return' | false
    true      | 'return' | false
    false     | 'throw'  | false
    true      | 'throw'  | false
    false     | 'cancel' | false
    true      | 'cancel' | false
    true      | 'return' | true
  }

  def "manual scope survives child completion after nested withContext"() {
    when:
    new StandardSuspensionHandoffTests().runNestedContextChange()

    then:
    noExceptionThrown()
  }

  def "dispatcher switch publishes manual scope before source restoration"() {
    when:
    new StandardSuspensionHandoffTests().runDispatcherChange()

    then:
    noExceptionThrown()
  }

  def "standard #kind suspension preserves manual scope with cancellation #cancelled and nesting #nested"() {
    setup:
    new StandardSuspensionHandoffTests().run(kind, cancelled, nested, {
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
    kind          | cancelled | nested
    'cancellable' | false     | false
    'cancellable' | true      | false
    'safe'        | false     | false
    'yield'       | false     | false
    'delay'       | false     | false
    'select'      | false     | false
    'cancellable' | false     | true
  }
}
