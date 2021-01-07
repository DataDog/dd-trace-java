package datadog.trace.bootstrap;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import lombok.extern.slf4j.Slf4j;

/**
 * A classloader which maintains a package index of isolated delegate classloaders, and delegates
 * class loads according to package. Loads classes itself according to package, but delegates
 * upwards when a class cannot be loaded.
 */
@Slf4j
public class DatadogClassLoader extends URLClassLoader {
  static {
    ClassLoader.registerAsParallelCapable();
  }

  protected final InternalJarURLHandler internalJarURLHandler;

  // Calling java.lang.instrument.Instrumentation#appendToBootstrapClassLoaderSearch
  // adds a jar to the bootstrap class lookup, but not to the resource lookup.
  // As a workaround, we keep a reference to the bootstrap jar
  // to use only for resource lookups.
  private final ClassLoader bootstrapProxy;
  private final String classLoaderName;

  private String lastPackage = null;

  public DatadogClassLoader(
      final URL bootstrapJarLocation,
      final String internalJarFileName,
      final ClassLoader bootstrapProxy,
      final ClassLoader parent) {
    super(new URL[] {}, parent);
    this.bootstrapProxy = bootstrapProxy;
    this.classLoaderName = null == internalJarFileName ? "datadog" : internalJarFileName;
    this.internalJarURLHandler =
        new InternalJarURLHandler(internalJarFileName, bootstrapJarLocation);
    try {
      // The fields of the URL are mostly dummy.  InternalJarURLHandler is the only important
      // field.  If extending this class from Classloader instead of URLClassloader required less
      // boilerplate it could be used and the need for dummy fields would be reduced
      addURL(new URL("x-internal-jar", null, 0, "/", internalJarURLHandler));
    } catch (final MalformedURLException e) {
      // This can't happen with current URL constructor
      log.error("URL malformed.  Unsupported JDK?", e);
    }
  }

  @Override
  public URL getResource(final String resourceName) {
    final URL bootstrapResource = bootstrapProxy.getResource(resourceName);
    if (null == bootstrapResource) {
      return super.getResource(resourceName);
    } else {
      return bootstrapResource;
    }
  }

  /**
   * @param className binary name of class
   * @return true if this loader has attempted to load the given class
   */
  public boolean hasLoadedClass(final String className) {
    return findLoadedClass(className) != null;
  }

  Class<?> loadFromPackage(String packageName, String name) throws ClassNotFoundException {
    InternalJarURLHandler.Lock packageLock = internalJarURLHandler.getPackageLock(packageName);
    if (null != packageLock) {
      synchronized (packageLock) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded != null) {
          return loaded;
        }
        if (packageLock.delegateFailureToFindClass()) {
          return findClass(name);
        }
      }
    }
    return super.loadClass(name);
  }

  String getPackageName(final String className) {
    // intentionally not thread-safe: the worst case scenario is excess allocation/lookups
    String packageName = lastPackage;
    if (null == packageName || !className.startsWith(packageName)) {
      int end = className.lastIndexOf('.');
      if (end != -1) {
        packageName = className.substring(0, end);
        this.lastPackage = packageName;
      } else {
        packageName = "";
      }
    }
    return packageName;
  }

  public ClassLoader getBootstrapProxy() {
    return bootstrapProxy;
  }

  /**
   * A stand-in for the bootstrap classloader. Used to look up bootstrap resources and resources
   * appended by instrumentation.
   *
   * <p>This class is thread safe.
   */
  public static final class BootstrapClassLoaderProxy extends URLClassLoader {
    static {
      ClassLoader.registerAsParallelCapable();
    }

    public BootstrapClassLoaderProxy(final URL url) {
      super(new URL[] {url}, null);
    }

    public BootstrapClassLoaderProxy() {
      super(new URL[0], null);
    }

    @Override
    public void addURL(final URL url) {
      super.addURL(url);
    }

    @Override
    protected Class<?> findClass(final String name) throws ClassNotFoundException {
      throw new ClassNotFoundException(name);
    }
  }

  @Override
  public String toString() {
    return classLoaderName;
  }

  public static class DelegateClassLoader extends DatadogClassLoader {
    static {
      ClassLoader.registerAsParallelCapable();
    }

    private final DatadogClassLoader shared;

    /**
     * Construct a new DatadogClassLoader
     *
     * @param bootstrapJarLocation Used for resource lookups.
     * @param internalJarFileName File name of the internal jar
     * @param parent Classloader parent. Should null (bootstrap), or the platform classloader for
     *     java 9+.
     */
    public DelegateClassLoader(
        final URL bootstrapJarLocation,
        final String internalJarFileName,
        final ClassLoader bootstrapProxy,
        final ClassLoader parent,
        final ClassLoader shared) {
      super(bootstrapJarLocation, internalJarFileName, bootstrapProxy, parent);
      this.shared = (DatadogClassLoader) shared;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      String packageName = shared.getPackageName(name);
      InternalJarURLHandler.Lock packageLock = internalJarURLHandler.getPackageLock(packageName);
      if (null != packageLock) {
        synchronized (packageLock) {
          Class<?> loaded = findLoadedClass(name);
          if (loaded != null) {
            return loaded;
          }
          if (packageLock.delegateFailureToFindClass()) {
            return findClass(name);
          }
        }
      }
      return shared.loadFromPackage(packageName, name);
    }
  }
}
