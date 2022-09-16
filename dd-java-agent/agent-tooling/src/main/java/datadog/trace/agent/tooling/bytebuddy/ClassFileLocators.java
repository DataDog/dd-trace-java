package datadog.trace.agent.tooling.bytebuddy;

import static datadog.trace.bootstrap.AgentClassLoading.LOCATING_CLASS;
import static datadog.trace.util.Strings.getResourceName;

import datadog.trace.agent.tooling.Utils;
import datadog.trace.agent.tooling.WeakCaches;
import datadog.trace.api.Config;
import datadog.trace.api.function.Function;
import datadog.trace.bootstrap.WeakCache;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLConnection;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.ClassFileLocator.Resolution;
import net.bytebuddy.utility.StreamDrainer;

/**
 * Locate resources with the loading classloader. Because of a quirk with the way classes appended
 * to the bootstrap classpath work, we first check our bootstrap proxy. If the loading classloader
 * cannot find the desired resource, check up the classloader hierarchy until a resource is found.
 */
public final class ClassFileLocators {
  private static final Function<ClassLoader, DDClassFileLocator> NEW_CLASS_FILE_LOCATOR =
      new Function<ClassLoader, DDClassFileLocator>() {
        @Override
        public DDClassFileLocator apply(ClassLoader input) {
          return new DDClassFileLocator(input);
        }
      };

  private static final WeakCache<ClassLoader, DDClassFileLocator> classFileLocators =
      WeakCaches.newWeakCache(64);

  private static final ClassFileLocator bootClassFileLocator =
      new ClassFileLocator() {
        @Override
        public Resolution locate(String className) throws IOException {
          String resourceName = getResourceName(className);
          Resolution resolution = loadClassResource(Utils.getBootstrapProxy(), resourceName);
          return resolution != null ? resolution : new Resolution.Illegal(className);
        }

        @Override
        public void close() {
          // nothing to close
        }
      };

  public static ClassFileLocator classFileLocator(final ClassLoader classLoader) {
    return null != classLoader
        ? classFileLocators.computeIfAbsent(classLoader, NEW_CLASS_FILE_LOCATOR)
        : bootClassFileLocator;
  }

  static final class DDClassFileLocator extends WeakReference<ClassLoader>
      implements ClassFileLocator {

    private static final boolean NO_CLASSLOADER_EXCLUDES =
        Config.get().getExcludedClassLoaders().isEmpty();

    public DDClassFileLocator(final ClassLoader classLoader) {
      super(classLoader);
    }

    @Override
    public Resolution locate(final String className) throws IOException {
      String resourceName = getResourceName(className);

      // try bootstrap first
      Resolution resolution = loadClassResource(Utils.getBootstrapProxy(), resourceName);
      ClassLoader cl = get();

      // now go up the classloader hierarchy
      if (null == resolution && null != cl) {
        LOCATING_CLASS.begin();
        try {
          do {
            if (NO_CLASSLOADER_EXCLUDES
                || !Config.get().getExcludedClassLoaders().contains(cl.getClass().getName())) {
              resolution = loadClassResource(cl, resourceName);
            }
            cl = cl.getParent();
          } while (null == resolution && null != cl);
        } finally {
          LOCATING_CLASS.end();
        }
      }

      return resolution != null ? resolution : new Resolution.Illegal(className);
    }

    @Override
    public void close() {
      // nothing to close
    }
  }

  static Resolution loadClassResource(ClassLoader classLoader, String resourceName) {
    URL url = classLoader.getResource(resourceName);
    return null != url ? new LazyResolution(url) : null;
  }

  private ClassFileLocators() {}

  public static final class LazyResolution implements Resolution {
    private final URL url;

    LazyResolution(URL url) {
      this.url = url;
    }

    public URL url() {
      return url;
    }

    @Override
    public boolean isResolved() {
      return true;
    }

    @Override
    public byte[] resolve() {
      try {
        URLConnection uc = url.openConnection();
        uc.setUseCaches(false);
        try (InputStream in = uc.getInputStream()) {
          return StreamDrainer.DEFAULT.drain(in);
        }
      } catch (IOException e) {
        throw new IllegalStateException("Error while reading class file", e);
      }
    }
  }
}
