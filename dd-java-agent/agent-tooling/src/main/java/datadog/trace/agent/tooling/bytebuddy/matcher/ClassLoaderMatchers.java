package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.bootstrap.AgentClassLoading.PROBING_CLASSLOADER;
import static net.bytebuddy.matcher.ElementMatchers.any;

import datadog.trace.agent.tooling.WeakCaches;
import datadog.trace.api.Config;
import datadog.trace.api.Tracer;
import datadog.trace.api.function.Function;
import datadog.trace.bootstrap.PatchLogger;
import datadog.trace.bootstrap.WeakCache;
import datadog.trace.util.Strings;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClassLoaderMatchers {
  private static final Logger log = LoggerFactory.getLogger(ClassLoaderMatchers.class);

  public static final ElementMatcher<ClassLoader> ANY_CLASS_LOADER = any();

  private static final ClassLoader BOOTSTRAP_CLASSLOADER = null;

  private static final boolean HAS_CLASSLOADER_EXCLUDES =
      !Config.get().getExcludedClassLoaders().isEmpty();

  /* Cache of classloader-instance -> (true|false). True = skip instrumentation. False = safe to instrument. */
  private static final WeakCache<ClassLoader, Boolean> skipCache = WeakCaches.newWeakCache(64);

  /** A private constructor that must not be invoked. */
  private ClassLoaderMatchers() {
    throw new UnsupportedOperationException();
  }

  public static boolean skipClassLoader(final ClassLoader loader) {
    if (loader == BOOTSTRAP_CLASSLOADER) {
      // Don't skip bootstrap loader
      return false;
    }
    if (canSkipClassLoaderByName(loader)) {
      return true;
    }
    Boolean v = skipCache.getIfPresent(loader);
    if (v != null) {
      return v;
    }
    // when ClassloadingInstrumentation is active, checking delegatesToBootstrap() below is not
    // required, because ClassloadingInstrumentation forces all class loaders to load all of the
    // classes in Constants.BOOTSTRAP_PACKAGE_PREFIXES directly from the bootstrap class loader
    //
    // however, at this time we don't want to introduce the concept of a required instrumentation,
    // and we don't want to introduce the concept of the tooling code depending on whether or not
    // a particular instrumentation is active (mainly because this particular use case doesn't
    // seem to justify introducing either of these new concepts)
    v = !delegatesToBootstrap(loader);
    skipCache.put(loader, v);
    return v;
  }

  public static boolean canSkipClassLoaderByName(final ClassLoader loader) {
    String classLoaderName = loader.getClass().getName();
    switch (classLoaderName) {
      case "org.codehaus.groovy.runtime.callsite.CallSiteClassLoader":
      case "sun.reflect.DelegatingClassLoader":
      case "jdk.internal.reflect.DelegatingClassLoader":
      case "clojure.lang.DynamicClassLoader":
      case "org.apache.cxf.common.util.ASMHelper$TypeHelperClassLoader":
      case "sun.misc.Launcher$ExtClassLoader":
      case "datadog.trace.bootstrap.DatadogClassLoader":
        return true;
    }
    if (HAS_CLASSLOADER_EXCLUDES) {
      return Config.get().getExcludedClassLoaders().contains(classLoaderName);
    }
    return false;
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
      hasClassMatchers.put(className, matcher = new HasClassMatcher(hasClassMatchers.size()));
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

  public static void reset() {
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
  static final WeakCache<ClassLoader, BitSet> hasClassCache = WeakCaches.newWeakCache(64);

  static final BitSet NO_CLASS_NAME_MATCHES = new BitSet();

  /** Function that generates a has-class mask for a given class-loader. */
  static final Function<ClassLoader, BitSet> buildHasClassMask =
      new Function<ClassLoader, BitSet>() {
        @Override
        public BitSet apply(ClassLoader input) {
          return buildHasClassMask(input);
        }
      };

  static BitSet buildHasClassMask(ClassLoader cl) {
    PROBING_CLASSLOADER.begin();
    try {
      BitSet hasClassMask = NO_CLASS_NAME_MATCHES;
      for (int hasClassId = hasClassResourceNames.size() - 1; hasClassId >= 0; hasClassId--) {
        try {
          if (cl.getResource(hasClassResourceNames.get(hasClassId)) != null) {
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
    protected boolean doMatch(final ClassLoader cl) {
      return hasClassCache.computeIfAbsent(cl, buildHasClassMask).get(hasClassId);
    }
  }
}
