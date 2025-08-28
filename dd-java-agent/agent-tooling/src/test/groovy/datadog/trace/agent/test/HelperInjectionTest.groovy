package datadog.trace.agent.test

import datadog.trace.agent.tooling.HelperInjector
import datadog.trace.agent.tooling.Utils
import datadog.trace.test.util.DDSpecification
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.loading.ClassInjector
import spock.lang.Ignore

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.utils.ClasspathUtils.isClassLoaded
import static datadog.trace.test.util.GCUtils.awaitGC

@Ignore
class HelperInjectionTest extends DDSpecification {
  static final String HELPER_CLASS_NAME = 'datadog.trace.agent.test.HelperClass'

  //@Flaky("awaitGC usage is flaky")
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

  //@Flaky("awaitGC usage is flaky")
  def "check hard references on class injection"() {
    setup:

    // Copied from HelperInjector:
    final ClassFileLocator locator =
      ClassFileLocator.ForClassLoader.of(Utils.getAgentClassLoader())
    final byte[] classBytes = locator.locate(HELPER_CLASS_NAME).resolve()
    final TypeDescription typeDesc =
      new TypeDescription.Latent(
      HELPER_CLASS_NAME, 0, null, Collections.<TypeDescription.Generic> emptyList())

    AtomicReference<URLClassLoader> emptyLoader = new AtomicReference<>(new URLClassLoader(new URL[0], (ClassLoader) null))
    AtomicReference<ClassInjector> injector = new AtomicReference<>(new ClassInjector.UsingReflection(emptyLoader.get()))
    injector.get().inject([(typeDesc): classBytes])

    when:
    def injectorRef = new WeakReference(injector.get())
    injector.set(null)

    awaitGC(injectorRef)

    then:
    null == injectorRef.get()

    when:
    def loaderRef = new WeakReference(emptyLoader.get())
    emptyLoader.set(null)

    awaitGC(loaderRef)

    then:
    null == loaderRef.get()
  }
}
