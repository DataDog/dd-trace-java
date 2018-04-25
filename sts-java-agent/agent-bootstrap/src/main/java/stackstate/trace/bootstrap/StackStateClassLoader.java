package stackstate.trace.bootstrap;

import java.net.URL;
import java.net.URLClassLoader;

/** Classloader used to run the core stackstate agent. */
public class StackStateClassLoader extends URLClassLoader {
  // Calling java.lang.instrument.Instrumentation#appendToBootstrapClassLoaderSearch
  // adds a jar to the bootstrap class lookup, but not to the resource lookup.
  // As a workaround, we keep a reference to the bootstrap jar
  // to use only for resource lookups.
  private final ClassLoader bootstrapResourceLocator;

  /**
   * Construct a new StackStateClassLoader
   *
   * @param bootstrapJarLocation Used for resource lookups.
   * @param agentJarLocation Classpath of this classloader.
   * @param parent Classloader parent. Should null (bootstrap), or the platform classloader for java
   *     9+.
   */
  public StackStateClassLoader(URL bootstrapJarLocation, URL agentJarLocation, ClassLoader parent) {
    super(new URL[] {agentJarLocation}, parent);
    bootstrapResourceLocator = new URLClassLoader(new URL[] {bootstrapJarLocation}, null);
  }

  @Override
  public URL getResource(String resourceName) {
    final URL bootstrapResource = bootstrapResourceLocator.getResource(resourceName);
    if (null == bootstrapResource) {
      return super.getResource(resourceName);
    } else {
      return bootstrapResource;
    }
  }
}
