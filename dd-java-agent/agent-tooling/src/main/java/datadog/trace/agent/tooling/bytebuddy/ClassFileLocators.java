package datadog.trace.agent.tooling.bytebuddy;

import static datadog.trace.bootstrap.AgentClassLoading.LOCATING_CLASS;
import static datadog.trace.util.Strings.getResourceName;

import datadog.instrument.utils.ClassLoaderValue;
import datadog.trace.agent.tooling.InstrumenterMetrics;
import datadog.trace.agent.tooling.Utils;
import datadog.trace.api.InstrumenterConfig;
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
  private static final ClassLoaderValue<DDClassFileLocator> classFileLocators =
      new ClassLoaderValue<DDClassFileLocator>() {
        @Override
        protected DDClassFileLocator computeValue(ClassLoader cl) {
          return new DDClassFileLocator(cl);
        }
      };

  private static final ClassFileLocator bootClassFileLocator =
      new ClassFileLocator() {
        @Override
        public Resolution locate(String className) throws IOException {
          String resourceName = getResourceName(className);
          long fromTick = InstrumenterMetrics.tick();
          Resolution resolution = loadClassResource(Utils.getBootstrapProxy(), resourceName);
          if (resolution != null) {
            InstrumenterMetrics.resolveClassFile(fromTick);
            return resolution;
          } else {
            InstrumenterMetrics.missingClassFile(fromTick);
            return new Resolution.Illegal(className);
          }
        }

        @Override
        public void close() {
          // nothing to close
        }
      };

  public static ClassFileLocator classFileLocator(final ClassLoader classLoader) {
    return null != classLoader ? classFileLocators.get(classLoader) : bootClassFileLocator;
  }

  static final class DDClassFileLocator extends WeakReference<ClassLoader>
      implements ClassFileLocator {

    private static final boolean NO_CLASSLOADER_EXCLUDES =
        InstrumenterConfig.get().getExcludedClassLoaders().isEmpty();

    public DDClassFileLocator(final ClassLoader classLoader) {
      super(classLoader);
    }

    @Override
    public Resolution locate(final String className) throws IOException {
      String resourceName = getResourceName(className);
      long fromTick = InstrumenterMetrics.tick();

      // try bootstrap first
      Resolution resolution = loadClassResource(Utils.getBootstrapProxy(), resourceName);
      if (null != resolution) {
        InstrumenterMetrics.resolveClassFile(fromTick);
        return resolution;
      }

      LOCATING_CLASS.begin();
      try {
        // now go up the classloader hierarchy
        for (ClassLoader cl = get(); null != cl; cl = cl.getParent()) {
          if (NO_CLASSLOADER_EXCLUDES
              || !InstrumenterConfig.get()
                  .getExcludedClassLoaders()
                  .contains(cl.getClass().getName())) {
            resolution = loadClassResource(cl, resourceName);
            if (null != resolution) {
              InstrumenterMetrics.resolveClassFile(fromTick);
              return resolution;
            }
          }
        }
      } finally {
        LOCATING_CLASS.end();
      }

      InstrumenterMetrics.missingClassFile(fromTick);
      return new Resolution.Illegal(className);
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
    private static final Boolean USE_URL_CACHES = InstrumenterConfig.get().isResolverUseUrlCaches();

    private final URL url;
    private byte[] bytecode;

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
      if (null == bytecode) {
        try {
          URLConnection uc = url.openConnection();
          if (null != USE_URL_CACHES) {
            uc.setUseCaches(USE_URL_CACHES);
          }
          try (InputStream in = uc.getInputStream()) {
            bytecode = StreamDrainer.DEFAULT.drain(in);
          }
        } catch (IOException e) {
          throw new IllegalStateException("Error while reading class file", e);
        }
      }
      return bytecode;
    }
  }
}
