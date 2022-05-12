package datadog.trace.agent.tooling.bytebuddy;

import static datadog.trace.bootstrap.AgentClassLoading.LOCATING_CLASS;
import static datadog.trace.util.Strings.getResourceName;

import datadog.trace.agent.tooling.Utils;
import datadog.trace.api.Config;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.utility.StreamDrainer;

/**
 * Locate resources with the loading classloader. Because of a quirk with the way classes appended
 * to the bootstrap classpath work, we first check our bootstrap proxy. If the loading classloader
 * cannot find the desired resource, check up the classloader hierarchy until a resource is found.
 */
public final class DDClassFileLocators {

  public static ClassFileLocator classFileLocator(final ClassLoader classLoader) {
    return new DDClassFileLocator(classLoader);
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

    private static Resolution loadClassResource(
        final ClassLoader classLoader, final String resourceName) throws IOException {
      try {
        try (InputStream in = classLoader.getResourceAsStream(resourceName)) {
          if (null != in) {
            return new Resolution.Explicit(StreamDrainer.DEFAULT.drain(in));
          }
          return null;
        }
      } catch (IllegalStateException ignored) {
        return null;
      }
    }
  }
}
