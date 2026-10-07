import datadog.trace.instrumentation.kotlin.coroutines.AbstractStandardSuspensionHandoffTest
import datadog.trace.instrumentation.kotlin.coroutines.StandardSuspensionHandoffTests

class StandardSuspensionHandoffTest extends AbstractStandardSuspensionHandoffTest {
  def "undispatched coroutine can replay its restoration token"() {
    when:
    new StandardSuspensionHandoffTests().runReplayedRestoration()

    then:
    noExceptionThrown()
  }
}
