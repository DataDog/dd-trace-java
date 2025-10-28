package datadog.trace.agent.test

import datadog.trace.agent.tooling.HelperInjector
import datadog.trace.agent.tooling.Utils
import datadog.trace.test.util.DDSpecification

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.utils.ClasspathUtils.isClassLoaded
import static datadog.trace.test.util.GCUtils.awaitGC

class HelperInjectionTest extends DDSpecification {
  static final String HELPER_CLASS_NAME = 'datadog.trace.agent.test.HelperClass'

  def "helpers injected to non-delegating classloader"() {
    setup:
    HelperInjector injector = new HelperInjector(false, "test", HELPER_CLASS_NAME)
    AtomicReference<URLClassLoader> emptyLoader = new AtomicReference<>(new URLClassLoader(new URL[0], (ClassLoader) null))

    when:
    emptyLoader.get().loadClass(HELPER_CLASS_NAME)
    then:
    thrown ClassNotFoundException

    when:
    injector.transform(null, null, emptyLoader.get(), null, null)
    emptyLoader.get().loadClass(HELPER_CLASS_NAME)
    then:
    isClassLoaded(HELPER_CLASS_NAME, emptyLoader.get())
    // injecting into emptyLoader should not load on agent's classloader
    !isClassLoaded(HELPER_CLASS_NAME, Utils.getAgentClassLoader())

    when: "references to emptyLoader are gone"
    emptyLoader.get().close() // cleanup
    def ref = new WeakReference(emptyLoader.get())
    emptyLoader.set(null)

    awaitGC(ref)

    then: "HelperInjector doesn't prevent it from being collected"
    null == ref.get()
  }
}
