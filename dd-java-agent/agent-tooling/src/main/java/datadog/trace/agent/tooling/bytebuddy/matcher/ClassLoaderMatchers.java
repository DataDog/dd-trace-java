package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.bootstrap.AgentClassLoading.PROBING_CLASSLOADER;
import static net.bytebuddy.matcher.ElementMatchers.any;

import datadog.instrument.utils.ClassLoaderValue;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.Tracer;
import datadog.trace.bootstrap.PatchLogger;
import datadog.trace.util.Strings;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClassLoaderMatchers {
  private static final Logger log = LoggerFactory.getLogger(ClassLoaderMatchers.class);

  public static final ElementMatcher.Junction<ClassLoader> ANY_CLASS_LOADER = any();

  private static final ClassLoader BOOTSTRAP_CLASSLOADER = null;

  private static final Set<String> EXCLUDED_CLASSLOADER_NAMES =
      InstrumenterConfig.get().getExcludedClassLoaders();

  private static final boolean CHECK_EXCLUDES = !EXCLUDED_CLASSLOADER_NAMES.isEmpty();

  /** A private constructor that must not be invoked. */
  private ClassLoaderMatchers() {
    throw new UnsupportedOperationException();
  }

  public static boolean canSkipClassLoaderByName(final ClassLoader loader) {
    String classLoaderName = loader.getClass().getName();
    switch (classLoaderName) {
      case "org.codehaus.groovy.runtime.callsite.CallSiteClassLoader":
      case "sun.reflect.DelegatingClassLoader":
      case "jdk.internal.reflect.DelegatingClassLoader":
      case "org.jvnet.hk2.internal.DelegatingClassLoader":
      case "clojure.lang.DynamicClassLoader":
      case "org.apache.cxf.common.util.ASMHelper$TypeHelperClassLoader":
      case "com.ibm.xml.xlxp2.jaxb.codegen.AbstractGeneratedStubFactory$RootStubClassLoader":
      case "sun.misc.Launcher$ExtClassLoader":
      case "org.springframework.context.support.ContextTypeMatchClassLoader$ContextOverridingClassLoader":
      case "org.openjdk.nashorn.internal.runtime.ScriptLoader":
      case "jdk.nashorn.internal.runtime.ScriptLoader":
      case "org.codehaus.janino.ByteArrayClassLoader":
      case "org.eclipse.persistence.internal.jaxb.JaxbClassLoader":
      case "com.alibaba.fastjson.util.ASMClassLoader":
      case "datadog.trace.bootstrap.DatadogClassLoader":
      case "datadog.trace.bootstrap.InstrumentationClassLoader":
        return true;
    }
    if (CHECK_EXCLUDES) {
      return EXCLUDED_CLASSLOADER_NAMES.contains(classLoaderName);
    }
    return false;
  }

  public static boolean incompatibleClassLoader(final ClassLoader loader) {
    return loader != BOOTSTRAP_CLASSLOADER && hasClassMask(loader) == INCOMPATIBLE_CLASS_LOADER;
    // note: must use '==' to distinguish INCOMPATIBLE_CLASS_LOADER from NO_CLASS_NAME_MATCHES
  }

  /**
   * NOTICE: Does not match the bootstrap classpath. Don't use with classes expected to be on the
   * bootstrap.
   *
   * @param className the className to match.
   * @return true if class is available as a resource, not on the bootstrap classloader.
   */
  public static ElementMatcher.Junction<ClassLoader> hasClassNamed(String className) {
    ElementMatcher.Junction<ClassLoader> matcher = hasClassMatchers.get(className);
    if (null == matcher) {
      // each matcher is given an id based on where to find its resource-name in the sequence
      hasClassMatchers.put(className, matcher = new HasClassMatcher(hasClassResourceNames.size()));
      hasClassResourceNames.add(Strings.getResourceName(className));
    }
    return matcher;
  }

  /**
   * NOTICE: Does not match the bootstrap classpath. Don't use with classes expected to be on the
   * bootstrap.
   *
   * @param classNames the classNames to match.
   * @return true if any class is available as a resource and not the bootstrap classloader.
   */
  public static ElementMatcher.Junction<ClassLoader> hasClassNamedOneOf(
      final String... classNames) {
    ElementMatcher<ClassLoader>[] matchers = new ElementMatcher[classNames.length];
    for (int i = 0; i < matchers.length; i++) {
      matchers[i] = hasClassNamed(classNames[i]);
    }
    return new ElementMatcher.Junction.Disjunction<>(matchers);
  }

  public static void resetState() {
    hasClassCache.clear();
  }

  /**
   * TODO: this turns out to be useless with OSGi: {@code
   * org.eclipse.osgi.internal.loader.BundleLoader#isRequestFromVM} returns {@code true} when class
   * loading is issued from this check and {@code false} for 'real' class loads. We should come up
   * with some sort of hack to avoid this problem.
   */
  private static boolean delegatesToBootstrap(final ClassLoader loader) {
    boolean delegates = true;
    if (!loadsExpectedClass(loader, Tracer.class)) {
      log.debug("Loader {} failed to delegate to bootstrap dd-trace-api class", loader);
      delegates = false;
    }
    if (!loadsExpectedClass(loader, PatchLogger.class)) {
      log.debug("Loader {} failed to delegate to bootstrap agent-bootstrap class", loader);
      delegates = false;
    }
    return delegates;
  }

  private static boolean loadsExpectedClass(
      final ClassLoader loader, final Class<?> expectedClass) {
    try {
      return loader.loadClass(expectedClass.getName()) == expectedClass;
    } catch (final Throwable ignored) {
      return false;
    }
  }

  /** Mapping of class-name to has-class matcher. */
  static final Map<String, ElementMatcher.Junction<ClassLoader>> hasClassMatchers = new HashMap<>();

  /** Sequence of class resource-names, in order of assigned hasClassId. */
  static final List<String> hasClassResourceNames = new ArrayList<>();

  /** Cache of classloader-instance -> has-class mask. */
  static final ClassLoaderValue<BitSet> hasClassCache =
      new ClassLoaderValue<BitSet>() {
        @Override
        protected BitSet computeValue(ClassLoader cl) {
          return buildHasClassMask(cl);
        }
      };

  /** Distinct result used to mark an incompatible classloader that the tracer should skip. */
  static final BitSet INCOMPATIBLE_CLASS_LOADER = new BitSet();

  static final BitSet NO_CLASS_NAME_MATCHES = new BitSet();

  static BitSet hasClassMask(ClassLoader loader) {
    return hasClassCache.get(loader);
  }

  static BitSet buildHasClassMask(ClassLoader loader) {
    if (!delegatesToBootstrap(loader)) {
      // when ClassloadingInstrumentation is active this check is not strictly required,
      // because that instrumentation forces all class loaders to load classes covered by
      // Constants.BOOTSTRAP_PACKAGE_PREFIXES directly from the bootstrap class loader
      return INCOMPATIBLE_CLASS_LOADER;
    }
    PROBING_CLASSLOADER.begin();
    try {
      BitSet hasClassMask = NO_CLASS_NAME_MATCHES;
      for (int hasClassId = hasClassResourceNames.size() - 1; hasClassId >= 0; hasClassId--) {
        try {
          if (loader.getResource(hasClassResourceNames.get(hasClassId)) != null) {
            if (hasClassMask.isEmpty()) {
              hasClassMask = new BitSet(hasClassId + 1);
            }
            hasClassMask.set(hasClassId);
          }
        } catch (final Throwable ignored) {
          // continue to next check
        }
      }
      return hasClassMask;
    } finally {
      PROBING_CLASSLOADER.end();
    }
  }

  static final class HasClassMatcher extends ElementMatcher.Junction.ForNonNullValues<ClassLoader> {
    private final int hasClassId;

    private HasClassMatcher(int hasClassId) {
      this.hasClassId = hasClassId;
    }

    @Override
    protected boolean doMatch(final ClassLoader loader) {
      return hasClassMask(loader).get(hasClassId);
    }
  }
}
