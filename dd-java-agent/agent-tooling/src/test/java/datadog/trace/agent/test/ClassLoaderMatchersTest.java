package datadog.trace.agent.test;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.canSkipClassLoaderByName;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.incompatibleClassLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.DatadogClassLoader;
import datadog.trace.bootstrap.instrumentation.log.LogContextScopeListener;
import datadog.trace.test.util.DDJavaSpecification;
import java.net.URL;
import java.net.URLClassLoader;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ClassLoaderMatchersTest extends DDJavaSpecification {

  @Test
  void skipNonDelegatingClassloader() {
    ClassLoader badLoader = new NonDelegatingClassLoader();
    assertTrue(incompatibleClassLoader(badLoader));
  }

  @Test
  void skipsAgentClassloader() {
    ClassLoader agentLoader = new DatadogClassLoader();
    assertTrue(incompatibleClassLoader(agentLoader));
  }

  @Test
  void doesNotSkipEmptyClassloader() {
    ClassLoader emptyLoader = new ClassLoader() {};
    assertFalse(incompatibleClassLoader(emptyLoader));
  }

  @Test
  void doesNotSkipBootstrapClassloader() {
    assertFalse(incompatibleClassLoader(null));
  }

  @Test
  void datadogClassLoaderClassNameIsHardcodedInClassLoaderMatcher() {
    assertEquals("datadog.trace.bootstrap.DatadogClassLoader", DatadogClassLoader.class.getName());
  }

  @Test
  void helperClassNamesAreHardcodedInLogInstrumentations() {
    assertEquals(
        "datadog.trace.bootstrap.instrumentation.log.LogContextScopeListener",
        LogContextScopeListener.class.getName());
  }

  @TableTest({
    "scenario                        | loaderName                                                                                  ",
    "Groovy call sites               | org.codehaus.groovy.runtime.callsite.CallSiteClassLoader                                    ",
    "JDK 8 reflection                | sun.reflect.DelegatingClassLoader                                                           ",
    "JDK 9+ reflection               | jdk.internal.reflect.DelegatingClassLoader                                                  ",
    "HK2 delegation                  | org.jvnet.hk2.internal.DelegatingClassLoader                                                ",
    "Clojure dynamic classes         | clojure.lang.DynamicClassLoader                                                             ",
    "CXF generated helpers           | org.apache.cxf.common.util.ASMHelper$TypeHelperClassLoader                                  ",
    "IBM XML generated stubs         | com.ibm.xml.xlxp2.jaxb.codegen.AbstractGeneratedStubFactory$RootStubClassLoader             ",
    "JDK extension loader            | sun.misc.Launcher$ExtClassLoader                                                            ",
    "Spring type matching            | org.springframework.context.support.ContextTypeMatchClassLoader$ContextOverridingClassLoader",
    "Standalone Nashorn              | org.openjdk.nashorn.internal.runtime.ScriptLoader                                           ",
    "JDK Nashorn                     | jdk.nashorn.internal.runtime.ScriptLoader                                                   ",
    "Legacy Janino                   | org.codehaus.janino.ByteArrayClassLoader                                                    ",
    "Janino commons-compiler         | org.codehaus.commons.compiler.util.reflect.ByteArrayClassLoader                             ",
    "EclipseLink JAXB                | org.eclipse.persistence.internal.jaxb.JaxbClassLoader                                       ",
    "Fastjson generated classes      | com.alibaba.fastjson.util.ASMClassLoader                                                    ",
    "Datadog agent classes           | datadog.trace.bootstrap.DatadogClassLoader                                                  ",
    "Datadog instrumentation classes | datadog.trace.bootstrap.InstrumentationClassLoader                                          ",
    "Gosu evaluated programs         | gw.internal.gosu.compiler.SingleServingGosuClassLoader                                      ",
    "Drools core rule packages       | org.drools.core.rule.PackageClassLoader                                                     ",
    "Drools dynamic rule packages    | org.drools.wiring.dynamic.PackageClassLoader                                                ",
    "Drools Java dialect packages    | org.drools.core.rule.JavaDialectRuntimeData$PackageClassLoader                              ",
    "Drools dynamic project types    | org.drools.wiring.dynamic.DynamicProjectClassLoader$DefaultInternalTypesClassLoader         "
  })
  void skipsClassloaderByName(String loaderName) throws Exception {
    ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
    ClassLoader loader =
        new ByteBuddy()
            .subclass(ClassLoader.class)
            .name(loaderName)
            .make()
            .load(systemClassLoader, ClassLoadingStrategy.Default.CHILD_FIRST)
            .getLoaded()
            .getDeclaredConstructor(ClassLoader.class)
            .newInstance(systemClassLoader);

    assertTrue(canSkipClassLoaderByName(loader));
  }

  /** A URLClassloader which only delegates java.* classes */
  private static class NonDelegatingClassLoader extends URLClassLoader {
    NonDelegatingClassLoader() {
      super(new URL[0], null);
    }

    @Override
    public Class<?> loadClass(String className) throws ClassNotFoundException {
      if (className.startsWith("java.")) {
        return super.loadClass(className);
      }
      throw new ClassNotFoundException(className);
    }
  }
}
