import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.config.inversion.ConfigHelper
import datadog.trace.agent.test.TestClassShadowingExtension
import org.spockframework.mock.*
import org.spockframework.mock.runtime.MockInvocation

class TooManyInvocationsErrorListenerTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // Opt out of strict config validation - test module loads test instrumentations with fake names
    ConfigHelper.get().setConfigInversionStrict(ConfigHelper.StrictnessPolicy.TEST)
  }

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
    TestClassShadowingExtension.fixTooManyInvocationsError(error)
    error.getMessage()

    then:
    noExceptionThrown()
  }
}
