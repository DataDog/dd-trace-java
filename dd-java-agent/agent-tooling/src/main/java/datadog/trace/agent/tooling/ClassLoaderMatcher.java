package datadog.trace.agent.tooling;

import datadog.trace.bootstrap.PatchLogger;
import datadog.trace.bootstrap.WeakCache;
import io.opentracing.util.GlobalTracer;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.matcher.ElementMatcher;

@Slf4j
public final class ClassLoaderMatcher {
  public static final ClassLoader BOOTSTRAP_CLASSLOADER = null;

  /** A private constructor that must not be invoked. */
  private ClassLoaderMatcher() {
    throw new UnsupportedOperationException();
  }

  public static ElementMatcher.Junction.AbstractBase<ClassLoader> skipClassLoader() {
    return SkipClassLoaderMatcher.INSTANCE;
  }

  /**
   * NOTICE: Does not match the bootstrap classpath. Don't use with classes expected to be on the
   * bootstrap.
   *
   * @param classNames list of names to match. returns true if empty.
   * @return true if class is available as a resource and not the bootstrap classloader.
   */
  public static ElementMatcher.Junction.AbstractBase<ClassLoader> hasClassesNamed(
      final String... classNames) {
    return new ClassLoaderHasClassesNamedMatcher(classNames);
  }

  private static final class SkipClassLoaderMatcher
      extends ElementMatcher.Junction.AbstractBase<ClassLoader> {
    public static final SkipClassLoaderMatcher INSTANCE = new SkipClassLoaderMatcher();
    /* Cache of classloader-instance -> (true|false). True = skip instrumentation. False = safe to instrument. */
    private static final String DATADOG_CLASSLOADER_NAME =
        "datadog.trace.bootstrap.DatadogClassLoader";
    private static final WeakCache<ClassLoader, Boolean> skipCache = AgentTooling.newWeakCache();

    private SkipClassLoaderMatcher() {}

    @Override
    public boolean matches(final ClassLoader cl) {
      if (cl == BOOTSTRAP_CLASSLOADER) {
        // Don't skip bootstrap loader
        return false;
      }
      Boolean v = skipCache.getIfPresent(cl);
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
      v = shouldSkipClass(cl) || !delegatesToBootstrap(cl);
      skipCache.put(cl, v);
      return v;
    }

    private static boolean shouldSkipClass(final ClassLoader loader) {
      switch (loader.getClass().getName()) {
        case "org.codehaus.groovy.runtime.callsite.CallSiteClassLoader":
        case "sun.reflect.DelegatingClassLoader":
        case "jdk.internal.reflect.DelegatingClassLoader":
        case "clojure.lang.DynamicClassLoader":
        case "org.apache.cxf.common.util.ASMHelper$TypeHelperClassLoader":
        case "sun.misc.Launcher$ExtClassLoader":
        case DATADOG_CLASSLOADER_NAME:
          return true;
      }
      return false;
    }

    /**
     * TODO: this turns out to be useless with OSGi: {@code
     * org.eclipse.osgi.internal.loader.BundleLoader#isRequestFromVM} returns {@code true} when
     * class loading is issued from this check and {@code false} for 'real' class loads. We should
     * come up with some sort of hack to avoid this problem.
     */
    private static boolean delegatesToBootstrap(final ClassLoader loader) {
      boolean delegates = true;
      if (!loadsExpectedClass(loader, GlobalTracer.class)) {
        log.debug("loader {} failed to delegate bootstrap opentracing class", loader);
        delegates = false;
      }
      if (!loadsExpectedClass(loader, PatchLogger.class)) {
        log.debug("loader {} failed to delegate bootstrap datadog class", loader);
        delegates = false;
      }
      return delegates;
    }

    private static boolean loadsExpectedClass(
        final ClassLoader loader, final Class<?> expectedClass) {
      try {
        return loader.loadClass(expectedClass.getName()) == expectedClass;
      } catch (final ClassNotFoundException e) {
        return false;
      }
    }
  }

  private static class ClassLoaderHasClassesNamedMatcher
      extends ElementMatcher.Junction.AbstractBase<ClassLoader> {

    private final WeakCache<ClassLoader, Boolean> cache = AgentTooling.newWeakCache(25);

    private final String[] resources;

    private ClassLoaderHasClassesNamedMatcher(final String... classNames) {
      resources = classNames;
      for (int i = 0; i < resources.length; i++) {
        resources[i] = resources[i].replace(".", "/") + ".class";
      }
    }

    private boolean hasResources(final ClassLoader cl) {
      for (final String resource : resources) {
        if (cl.getResource(resource) == null) {
          return false;
        }
      }
      return true;
    }

    @Override
    public boolean matches(final ClassLoader cl) {
      if (cl == BOOTSTRAP_CLASSLOADER) {
        // Can't match the bootstrap classloader.
        return false;
      }
      final Boolean cached;
      if ((cached = cache.getIfPresent(cl)) != null) {
        return cached;
      }
      final boolean value = hasResources(cl);
      cache.put(cl, value);
      return value;
    }
  }
}
