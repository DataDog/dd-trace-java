import datadog.trace.agent.test.InstrumentationSpecification

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class ScalaFirstCompletedOfTest extends InstrumentationSpecification {

  def "releases callback continuation when firstCompletedOf unregisters it"() {
    setup:
    def executionContext = new QueuingExecutionContext()
    def utils = new FirstCompletedOfUtils(executionContext)
    def first = utils.newPromise()
    def second = utils.newPromise()
    def result

    when:
    runUnderTrace("parent") {
      result = utils.firstCompleted(first, second)
      first.success("first")
    }

    then:
    executionContext.queuedTaskCount() == 1
    executionContext.runNext()
    result.value().get().get() == "first"

    when:
    second.success("second")

    then:
    executionContext.queuedTaskCount() == 0
    assertTraces(1) {
      trace(1) {
        basicSpan(it, "parent")
      }
    }
  }

  def "does not release callback continuation when completion wins unregister race"() {
    setup:
    def executionContext = new QueuingExecutionContext()
    def utils = new FirstCompletedOfUtils(executionContext)
    def first = utils.newPromise()
    def second = utils.newPromise()

    when:
    runUnderTrace("parent") {
      utils.firstCompleted(first, second)
      first.success("first")
      second.success("second")
    }

    then:
    executionContext.queuedTaskCount() == 2

    when:
    executionContext.runNext()

    then:
    executionContext.queuedTaskCount() == 1
    TEST_WRITER.size() == 0

    when:
    executionContext.runNext()

    then:
    executionContext.queuedTaskCount() == 0
    assertTraces(1) {
      trace(1) {
        basicSpan(it, "parent")
      }
    }
  }

  def "preserves another callback when unregistering the #position of ManyCallbacks"() {
    setup:
    def executionContext = new QueuingExecutionContext()
    def utils = new FirstCompletedOfUtils(executionContext)
    def first = utils.newPromise()
    def second = utils.newPromise()
    def result
    def registerSurvivor = {
      runUnderTrace("survivor-parent") {
        utils.onComplete(second, {
          runUnderTrace("survivor-child") {}
        } as Runnable)
      }
    }

    when:
    if (survivorFirst) {
      registerSurvivor()
    }
    runUnderTrace("first-completed-parent") {
      result = utils.firstCompleted(first, second)
    }
    if (!survivorFirst) {
      registerSurvivor()
    }
    first.success("first")

    then:
    executionContext.queuedTaskCount() == 1
    executionContext.runNext()
    result.value().get().get() == "first"
    assertTraces(1) {
      trace(1) {
        basicSpan(it, "first-completed-parent")
      }
    }

    when:
    second.success("second")

    then:
    executionContext.queuedTaskCount() == 1
    TEST_WRITER.size() == 1

    when:
    executionContext.runNext()

    then:
    executionContext.queuedTaskCount() == 0
    assertTraces(2, SORT_TRACES_BY_NAMES) {
      trace(1) {
        basicSpan(it, "first-completed-parent")
      }
      trace(2, true) {
        basicSpan(it, "survivor-child", it.span(1))
        basicSpan(it, "survivor-parent")
      }
    }

    where:
    position | survivorFirst
    "head"   | true
    "tail"   | false
  }
}
