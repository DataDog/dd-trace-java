package datadog.trace.bootstrap;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A classloader which maintains a package index of isolated delegate classloaders, and delegates
 * class loads according to package. Loads classes itself according to package, but delegates
 * upwards when a class cannot be loaded.
 */
public class DatadogClassLoader extends URLClassLoader {

  private static final Logger log = LoggerFactory.getLogger(DatadogClassLoader.class);

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
  private final JarIndex jarIndex;

  private String lastPackage = null;

  public DatadogClassLoader(
      final URL bootstrapJarLocation,
      final String internalJarFileName,
      final ClassLoader bootstrapProxy,
      final ClassLoader parent) {
    super(new URL[] {}, parent);
    this.jarIndex =
        parent instanceof DatadogClassLoader
            ? ((DatadogClassLoader) parent).jarIndex
            : new JarIndex(bootstrapJarLocation);
    this.bootstrapProxy = bootstrapProxy;
    this.classLoaderName = null == internalJarFileName ? "datadog" : internalJarFileName;
    this.internalJarURLHandler =
        new InternalJarURLHandler(
            internalJarFileName, jarIndex.index.get(internalJarFileName), jarIndex.jarFile);
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
    if (internalJarURLHandler.hasPackage(packageName)) {
      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded != null) {
          return loaded;
        }
        if (!packageName.startsWith("java.")) {
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
      if (internalJarURLHandler.hasPackage(packageName)) {
        synchronized (getClassLoadingLock(name)) {
          Class<?> loaded = findLoadedClass(name);
          if (loaded != null) {
            return loaded;
          }
          if (!packageName.startsWith("java.")) {
            return findClass(name);
          }
        }
      }
      return shared.loadFromPackage(packageName, name);
    }
  }

  static final class JarIndex {
    private final HashMap<String, Set<String>> index;
    private final JarFile jarFile;

    private JarIndex(URL location) {
      this.index = new HashMap<>();
      JarFile jarFile = null;
      try {
        if (location != null) {
          jarFile = new JarFile(new File(location.toURI()), false);
          String currentFilePrefix = "$";
          int prefixLength = Integer.MAX_VALUE;
          Set<String> packages = null;
          final Enumeration<JarEntry> entries = jarFile.entries();
          while (entries.hasMoreElements()) {
            final JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() && !name.startsWith("META-INF/")) {
              if (!name.startsWith(currentFilePrefix)) {
                int end = name.indexOf('/');
                currentFilePrefix = name.substring(0, end);
                packages = new HashSet<>();
                index.put(currentFilePrefix, packages);
                prefixLength = end + 1;
              }
              if (name.length() > prefixLength && null != packages) {
                String dir = name.substring(prefixLength, name.length() - 1);
                String currentPackage = dir.replace('/', '.');
                packages.add(currentPackage);
              }
            }
          }
        }
      } catch (final URISyntaxException | IOException e) {
        log.error("Unable to read internal jar", e);
      }
      this.jarFile = jarFile;
    }

    public Set<String> getPackages(String namespace) {
      return index.get(namespace);
    }

    public JarFile getJarFile() {
      return jarFile;
    }
  }
}
