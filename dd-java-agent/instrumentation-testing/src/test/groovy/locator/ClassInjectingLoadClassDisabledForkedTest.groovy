package locator

import datadog.trace.agent.test.InstrumentationSpecification
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.utility.JavaModule
import spock.lang.Shared

import java.lang.instrument.ClassFileTransformer

/**
 * This test checks that we don't fall back to loadClass when it is disabled.
 */
class ClassInjectingLoadClassDisabledForkedTest extends InstrumentationSpecification {

  static volatile ClassFileTransformer extraTransformer = null

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.resolver.use.loadclass", "false")

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

  @Override
  void onTransformation(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded, DynamicType dynamicType) {
    transformed += typeDescription.name
  }

  @Shared
  def transformed = []

  def "should not find classes injected via defineClass"() {
    setup:
    def instrumented = new ClassInjectingTestInstrumentation.ToBeInstrumented("test")

    expect:
    !transformed.contains('locator.ClassInjectingTestInstrumentation$ToBeInstrumented')
    instrumented.message == "test:${ClassInjectingTransformer.NAME}"
  }
}
