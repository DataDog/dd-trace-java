import datadog.trace.instrumentation.kotlin.coroutines.AbstractStandardSuspensionHandoffTest
import datadog.trace.instrumentation.kotlin.coroutines.StandardSuspensionHandoffTests

class StandardSuspensionHandoffTest extends AbstractStandardSuspensionHandoffTest {
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
    // the next worker can briefly see the previous worker's element scope until it is closed;
    // that is expected, but the coroutine must never lose its base context or fail
    violations.keySet().findAll { !it.contains("leaked") && !it.contains("restore") }.isEmpty()
  }
}
