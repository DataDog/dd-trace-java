package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.bootstrap.AgentClassLoading.PROBING_CLASSLOADER;
import static datadog.trace.util.Strings.getResourceName;
import static net.bytebuddy.matcher.ElementMatchers.any;

import datadog.trace.agent.tooling.WeakCaches;
import datadog.trace.api.Config;
import datadog.trace.api.Tracer;
import datadog.trace.bootstrap.PatchLogger;
import datadog.trace.bootstrap.WeakCache;
import java.util.Arrays;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClassLoaderMatchers {
  private static final Logger log = LoggerFactory.getLogger(ClassLoaderMatchers.class);

  public static final ElementMatcher<ClassLoader> ANY_CLASS_LOADER = any();

  private static final ClassLoader BOOTSTRAP_CLASSLOADER = null;
  private static final String DATADOG_CLASSLOADER_NAME =
      "datadog.trace.bootstrap.DatadogClassLoader";
  private static final String DATADOG_DELEGATE_CLASSLOADER_NAME =
      "datadog.trace.bootstrap.DatadogClassLoader$DelegateClassLoader";

  private static final boolean HAS_CLASSLOADER_EXCLUDES =
      !Config.get().getExcludedClassLoaders().isEmpty();

  /* Cache of classloader-instance -> (true|false). True = skip instrumentation. False = safe to instrument. */
  private static final WeakCache<ClassLoader, Boolean> skipCache = WeakCaches.newWeakCache();

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
      case DATADOG_CLASSLOADER_NAME:
      case DATADOG_DELEGATE_CLASSLOADER_NAME:
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
   * @param classNames list of names to match. returns true if empty.
   * @return true if class is available as a resource and not the bootstrap classloader.
   */
  public static ElementMatcher.Junction.AbstractBase<ClassLoader> hasClassesNamed(
      final String... classNames) {
    return new ClassLoaderHasClassesNamedMatcher(classNames);
  }

  /**
   * NOTICE: Does not match the bootstrap classpath. Don't use with classes expected to be on the
   * bootstrap.
   *
   * @param className the className to match.
   * @return true if class is available as a resource and not the bootstrap classloader.
   */
  public static ElementMatcher.Junction.AbstractBase<ClassLoader> hasClassesNamed(
      final String className) {
    return new ClassLoaderHasClassNamedMatcher(className);
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

  private abstract static class ClassLoaderHasNameMatcher
      extends ElementMatcher.Junction.AbstractBase<ClassLoader> {

    // Initialize this lazily because of startup ordering and muzzle plugin usage patterns
    private volatile WeakCache<ClassLoader, Boolean> cacheHolder = null;

    protected WeakCache<ClassLoader, Boolean> getCache() {
      if (cacheHolder == null) {
        synchronized (this) {
          if (cacheHolder == null) {
            cacheHolder = WeakCaches.newWeakCache(25);
          }
        }
      }
      return cacheHolder;
    }

    @Override
    public boolean matches(final ClassLoader cl) {
      if (cl == BOOTSTRAP_CLASSLOADER) {
        // Can't match the bootstrap classloader.
        return false;
      }
      final Boolean cached;
      WeakCache<ClassLoader, Boolean> cache = getCache();
      if ((cached = cache.getIfPresent(cl)) != null) {
        return cached;
      }
      final boolean value = checkMatch(cl);
      cache.put(cl, value);
      return value;
    }

    protected abstract boolean checkMatch(ClassLoader cl);
  }

  private static class ClassLoaderHasClassesNamedMatcher extends ClassLoaderHasNameMatcher {

    private final String[] resources;

    private ClassLoaderHasClassesNamedMatcher(final String... classNames) {
      resources = classNames;
      for (int i = 0; i < resources.length; i++) {
        resources[i] = getResourceName(resources[i]);
      }
    }

    protected boolean checkMatch(final ClassLoader cl) {
      PROBING_CLASSLOADER.begin();
      try {
        for (final String resource : resources) {
          if (cl.getResource(resource) == null) {
            return false;
          }
        }
        return true;
      } catch (final Throwable ignored) {
        return false;
      } finally {
        PROBING_CLASSLOADER.end();
      }
    }

    @Override
    public String toString() {
      return "ClassLoaderHasClassesNamedMatcher{named=" + Arrays.toString(resources) + "}";
    }
  }

  private static class ClassLoaderHasClassNamedMatcher extends ClassLoaderHasNameMatcher {

    private final String resource;

    private ClassLoaderHasClassNamedMatcher(final String className) {
      resource = getResourceName(className);
    }

    protected boolean checkMatch(final ClassLoader cl) {
      PROBING_CLASSLOADER.begin();
      try {
        return cl.getResource(resource) != null;
      } catch (final Throwable ignored) {
        return false;
      } finally {
        PROBING_CLASSLOADER.end();
      }
    }

    @Override
    public String toString() {
      return "ClassLoaderHasClassNamedMatcher{named='" + resource + "\'}";
    }
  }
}
