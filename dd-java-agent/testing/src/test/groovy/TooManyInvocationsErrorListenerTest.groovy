import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.SpockRunner
import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.spockframework.mock.IMockInteraction
import org.spockframework.mock.IMockMethod
import org.spockframework.mock.IMockObject
import org.spockframework.mock.IResponseGenerator
import org.spockframework.mock.TooManyInvocationsError
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
    final failure = new Failure(new Description(TooManyInvocationsErrorListenerTest, 'test'), error)

    when:
    failure.getMessage()

    then:
    thrown(StackOverflowError)

    when:
    final listener = new SpockRunner.TooManyInvocationsErrorListener()
    listener.testFailure(failure)
    failure.getMessage()

    then:
    noExceptionThrown()
  }
}
