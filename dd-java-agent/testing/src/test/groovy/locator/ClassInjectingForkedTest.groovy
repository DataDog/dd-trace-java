package locator

import datadog.trace.agent.test.AgentTestRunner
import net.bytebuddy.agent.builder.AgentBuilder

import java.lang.instrument.ClassFileTransformer

class ClassInjectingForkedTest extends AgentTestRunner {

  static volatile ClassFileTransformer extraTransformer = null

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()

    // Since this method is not at all configurePreAgent, but more like
    // configurePreAgentAndOhByTheWayBeforeEveryTest we need to not install
    // the extra transformer multiple times
    if (!extraTransformer) {
      AgentBuilder builder = new AgentBuilder.Default()
      builder = ClassInjectingTransformer.instrument(builder)
      extraTransformer = builder.installOn(INSTRUMENTATION)
    }
  }

  @Override
  protected void cleanupAfterAgent() {
    if (extraTransformer) {
      INSTRUMENTATION.removeTransformer(extraTransformer)
      extraTransformer = null
    }

    super.cleanupAfterAgent()
  }

  def "should find classes injected via defineClass"() {
    setup:
    def instrumented = new ClassInjectingTestInstrumentation.ToBeInstrumented("test")

    expect:
    instrumented.message == "test:instrumented:${ClassInjectingTransformer.NAME}"
  }
}
