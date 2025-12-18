import datadog.trace.agent.test.InstrumentationSpecification
import test.jsr14.Jsr14ClassLoader

class Jsr14ClassloadingTest extends InstrumentationSpecification {
  def "OSGI delegates to bootstrap class loader for agent classes using #args args"() {
    when:
    def clazz
    if (args == 1) {
      clazz = loader.loadClass("datadog.trace.api.GlobalTracer")
    } else {
      clazz = loader.loadClass("datadog.trace.api.GlobalTracer", false)
    }

    then:
    assert clazz != null
    assert clazz.getClassLoader() == null

    where:
    loader                 | args
    new Jsr14ClassLoader() | 1
    new Jsr14ClassLoader() | 2
  }
}
