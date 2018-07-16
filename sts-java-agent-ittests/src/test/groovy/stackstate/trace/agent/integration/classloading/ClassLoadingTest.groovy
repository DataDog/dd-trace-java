package stackstate.trace.agent.integration.classloading

import stackstate.trace.agent.test.IntegrationTestUtils
import stackstate.trace.api.Trace
import spock.lang.Specification

import static stackstate.trace.agent.test.IntegrationTestUtils.createJarWithClasses

class ClassLoadingTest extends Specification {

  /** Assert that we can instrument classloaders which cannot resolve agent advice classes. */
  def "instrument classloader without agent classes" () {
    setup:
    final URL[] classpath = [createJarWithClasses(ClassToInstrument, Trace)]
    final URLClassLoader loader = new URLClassLoader(classpath, (ClassLoader)null)

    when:
    loader.loadClass("stackstate.agent.TracingAgent")
    then:
    thrown ClassNotFoundException

    when:
    final Class<?> instrumentedClass = loader.loadClass(ClassToInstrument.getName())
    then:
    instrumentedClass.getClassLoader() == loader
  }

  def "can find bootstrap resources"() {
    expect:
    IntegrationTestUtils.getAgentClassLoader().getResources('stackstate/trace/api/Trace.class') != null
  }
}
