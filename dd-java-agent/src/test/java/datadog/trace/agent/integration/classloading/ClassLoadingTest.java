package datadog.trace.agent.integration.classloading;

import static datadog.trace.agent.test.IntegrationTestUtils.createJarWithClasses;
import static datadog.trace.agent.test.IntegrationTestUtils.runOnSeparateJvm;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.test.ClassToInstrument;
import datadog.test.ClassToInstrumentChild;
import datadog.trace.api.Trace;
import datadog.trace.test.util.GCUtils;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import jvmbootstraptest.IsolatedClassloading;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.tabletest.junit.TableTest;

@Timeout(10)
class ClassLoadingTest {

  private URL[] classpath;

  @BeforeEach
  void setup() throws IOException {
    classpath =
        new URL[] {
          createJarWithClasses(ClassToInstrument.class, ClassToInstrumentChild.class, Trace.class)
        };
  }

  /** Assert that we can instrument classloaders which cannot resolve agent advice classes. */
  @Test
  void instrumentClassloaderWithoutAgentClasses() throws ClassNotFoundException {
    // setup
    URLClassLoader loader = new URLClassLoader(classpath, null);

    // when
    // then
    assertThrows(
        ClassNotFoundException.class,
        () -> loader.loadClass("datadog.trace.instrumentation.trace_annotation.TraceAdvice"));

    // when
    Class<?> instrumentedClass = loader.loadClass(ClassToInstrument.class.getName());
    // then
    assertSame(loader, instrumentedClass.getClassLoader());
  }

  // @Flaky("awaitGC is flaky")
  @Test
  void makeSureByteBuddyDoesNotHoldStrongReferencesToClassLoader() throws Exception {
    // setup
    URLClassLoader loader = new URLClassLoader(classpath, null);
    WeakReference<URLClassLoader> ref = new WeakReference<>(loader);

    // when
    loader.loadClass(ClassToInstrument.class.getName());
    loader = null;

    GCUtils.awaitGC(ref);

    // then
    assertNull(ref.get());
  }

  static class CountingClassLoader extends URLClassLoader {
    int count = 0;

    CountingClassLoader(URL[] urls) {
      super(urls, null);
    }

    @Override
    public URL getResource(String name) {
      count++;
      return super.getResource(name);
    }
  }

  @Test
  void makeSureThatByteBuddyReadsClassesBytesOnlyOnce() throws ClassNotFoundException {
    // setup
    CountingClassLoader loader = new CountingClassLoader(classpath);

    // when
    // loader.loadClass("aaa")
    loader.loadClass(ClassToInstrument.class.getName());
    int countAfterFirstLoad = loader.count;
    loader.loadClass(ClassToInstrumentChild.class.getName());

    // then
    // ClassToInstrumentChild won't cause an additional getResource() because its TypeDescription
    // is created from transformation bytes.
    assertTrue(loader.count > 0);
    assertEquals(countAfterFirstLoad, loader.count);
  }

  @Test
  void makeSureThatByteBuddyDoesNotReuseCachedTypeDescriptionsBetweenDifferentClassloaders()
      throws ClassNotFoundException {
    // setup
    CountingClassLoader loader1 = new CountingClassLoader(classpath);
    CountingClassLoader loader2 = new CountingClassLoader(classpath);

    // when
    loader1.loadClass(ClassToInstrument.class.getName());
    loader2.loadClass(ClassToInstrument.class.getName());

    // then
    assertTrue(loader1.count > 0);
    assertTrue(loader2.count > 0);
    assertEquals(loader1.count, loader2.count);
  }

  @TableTest({
    "scenario  | name                    | onTestClasspath",
    "trace api | datadog.trace.api.Trace | true           "
    // spotless:off
    // This test case fails on ibm j9.  Perhaps this rule only applies to OpenJdk based jvms?
    // state  | datadog.trace.bootstrap.instrumentation.java.concurrent.State | false
    // spotless:on
  })
  void canFindClassesButNotResourcesLoadedOntoTheBootstrapClasspath(
      String name, boolean onTestClasspath) throws ClassNotFoundException {
    String resource = name.replace(".", "/") + ".class";
    // expect
    assertNotNull(Class.forName(name));

    // Resources from bootstrap injected jars can't be loaded.
    // https://github.com/raphw/byte-buddy/pull/496
    URL resourceUrl = ClassLoader.getSystemClassLoader().getResource(resource);
    if (onTestClasspath) {
      assertNotNull(resourceUrl);
    } else {
      assertNull(resourceUrl);
    }
  }

  @Test
  @Timeout(30)
  void classloaderInstrumentationWorksWithIsolatedClassloaders() throws Exception {
    // expect
    assertEquals(
        0,
        runOnSeparateJvm(
            IsolatedClassloading.class.getName(),
            asList("-Ddd.trace.debug=false", "-Ddd.jmxfetch.enabled=false"),
            emptyList(),
            emptyMap(),
            true));
  }
}
