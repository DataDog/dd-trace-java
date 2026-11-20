package datadog.trace.agent.test;

import static datadog.trace.agent.test.utils.ClasspathUtils.isClassLoaded;
import static datadog.trace.test.util.GCUtils.awaitGC;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.instrument.classinject.ClassInjector;
import datadog.trace.agent.tooling.HelperInjector;
import datadog.trace.agent.tooling.Utils;
import datadog.trace.test.util.DDJavaSpecification;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;

class HelperInjectionTest extends DDJavaSpecification {
  static final String HELPER_CLASS_NAME = "datadog.trace.agent.test.HelperClass";

  @Test
  void helpersInjectedToNonDelegatingClassloader() throws Exception {
    ClassInjector.enableClassInjection(ByteBuddyAgent.install());
    HelperInjector injector = new HelperInjector(false, "test", HELPER_CLASS_NAME);
    AtomicReference<URLClassLoader> emptyLoader =
        new AtomicReference<>(new URLClassLoader(new URL[0], null));

    assertThrows(
        ClassNotFoundException.class, () -> emptyLoader.get().loadClass(HELPER_CLASS_NAME));

    injector.transform(null, null, emptyLoader.get(), null, null);
    emptyLoader.get().loadClass(HELPER_CLASS_NAME);
    assertTrue(isClassLoaded(HELPER_CLASS_NAME, emptyLoader.get()));
    // injecting into emptyLoader should not load on agent's classloader
    assertFalse(isClassLoaded(HELPER_CLASS_NAME, Utils.getAgentClassLoader()));

    // references to emptyLoader are gone
    emptyLoader.get().close(); // cleanup
    WeakReference<URLClassLoader> ref = new WeakReference<>(emptyLoader.get());
    emptyLoader.set(null);

    awaitGC(ref);

    // HelperInjector doesn't prevent it from being collected
    assertNull(ref.get());
  }
}
