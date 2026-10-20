import datadog.trace.instrumentation.kotlin.coroutines.AbstractStandardSuspensionHandoffTest
import datadog.trace.instrumentation.kotlin.coroutines.StandardSuspensionHandoffTests

class StandardSuspensionHandoffTest extends AbstractStandardSuspensionHandoffTest {
  // withContext uses the context merge hook from coroutines 1.6.1 onward.
  def "context merge preserves an explicitly swapped span"() {
    when:
    new StandardSuspensionHandoffTests().runContextSwapAcrossMerge()
    TEST_WRITER.waitForTraces(1)
    def trace = TEST_WRITER.get(0)
    def spans = trace.collectEntries { [(it.operationName.toString()): it] }

    then:
    trace.size() == 3
    spans.swapped.parentId == spans.parent.spanId
    spans.child.parentId == spans.swapped.spanId
  }

  def "undispatched coroutine can replay its restoration token"() {
    when:
    new StandardSuspensionHandoffTests().runReplayedRestoration()

    then:
    noExceptionThrown()
  }

  def "scope-touching thread context elements do not corrupt the handed-off stack"() {
    when:
    def violations = new StandardSuspensionHandoffTests().runScopeTouchingElements(1000)

    then:
    // Overlapping workers can temporarily see each other's element scopes. The coroutine must
    // retain its base context, but restore callbacks can observe a different top during overlap.
    def expectedDuringOverlap = [
      'element scope leaked from previous worker',
      'before scope not on top at restore',
      'after scope not on top at restore',
      'before restore did not return to previous context',
      'after restore did not return to previous context'
    ]
    (violations.keySet() - expectedDuringOverlap).isEmpty()
  }
}
