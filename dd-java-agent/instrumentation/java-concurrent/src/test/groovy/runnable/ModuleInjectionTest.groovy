package runnable

import datadog.trace.agent.test.InstrumentationSpecification

import javax.swing.*

/**
 * This class tests that we correctly add module references when instrumenting
 */
class ModuleInjectionTest extends InstrumentationSpecification {
  /**
   * There's nothing special about RepaintManager other than
   * it's in a module (java.desktop) that doesn't read the "unnamed module" and it
   * creates an instrumented runnable in its constructor
   */
  def "test instrumenting java.desktop class"() {
    when:
    new RepaintManager()

    then:
    noExceptionThrown()
  }
}
