import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.SpockExtension
import org.spockframework.mock.*
import org.spockframework.mock.runtime.MockInvocation

class TooManyInvocationsErrorListenerTest extends AgentTestRunner {

  @SuppressWarnings('GroovyAccessibility')
  void 'test that listener modifies failure'() {
    setup:
    final error = new TooManyInvocationsError(Stub(IMockInteraction), [])
    error.acceptedInvocations.add(new MockInvocation(Stub(IMockObject),
      Stub(IMockMethod),
      [error],
      Stub(IResponseGenerator)))

    when:
    error.getMessage()

    then:
    thrown(StackOverflowError)

    when:
    SpockExtension.fixTooManyInvocationsError(error)
    error.getMessage()

    then:
    noExceptionThrown()
  }
}
