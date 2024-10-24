package datadog.trace.bootstrap;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Provides access to Datadog internal classes. */
public final class DatadogClassLoader extends SecureClassLoader {
  static {
    ClassLoader.registerAsParallelCapable();
  }

  private static final Logger log = LoggerFactory.getLogger(DatadogClassLoader.class);

  private final Set<String> definedPackages = new HashSet<>();

  private final JarFile agentJarFile;
  private final CodeSource agentCodeSource;
  private final String agentResourcePrefix;
  private final AgentJarIndex agentJarIndex;

  private final Object instrumentationClassLoaderLock = new Object();
  private volatile WeakReference<InstrumentationClassLoader> instrumentationClassLoader =
      new WeakReference<>(new InstrumentationClassLoader(this));

  public DatadogClassLoader(final URL agentJarURL, final ClassLoader parent) throws Exception {
    super(parent);

    agentJarFile = new JarFile(new File(agentJarURL.toURI()), false);
    agentCodeSource = new CodeSource(agentJarURL, (Certificate[]) null);
    agentResourcePrefix = "jar:file:" + agentJarFile.getName() + "!/";
    agentJarIndex = AgentJarIndex.readIndex(agentJarFile);
  }

  /** For testing purposes only. */
  public DatadogClassLoader() {
    super(null);

    agentCodeSource = null;
    agentJarFile = null;
    agentResourcePrefix = null;
    agentJarIndex = AgentJarIndex.emptyIndex();
  }

  @Override
  public URL getResource(final String name) {
    URL bootstrapResource = BootstrapProxy.INSTANCE.getResource(name);
    if (null != bootstrapResource) {
      return bootstrapResource;
    }
    return super.getResource(name);
  }

  @Override
  protected URL findResource(String name) {
    String entryName = agentJarIndex.resourceEntryName(name);
    if (null != entryName) {
      JarEntry jarEntry = agentJarFile.getJarEntry(entryName);
      if (null != jarEntry) {
        String location = agentResourcePrefix + entryName;
        try {
          return new URL(location);
        } catch (Exception e) {
          log.warn("Malformed location {}", location);
        }
      }
    }
    return null;
  }

  @Override
  protected Enumeration<URL> findResources(String name) {
    URL resource = findResource(name);
    if (null != resource) {
      return Collections.enumeration(Collections.singleton(resource));
    }
    return Collections.emptyEnumeration();
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    if (name.startsWith("datadog.trace.instrumentation.")
        && (name.endsWith("$Muzzle") || name.endsWith("Instrumentation"))) {
      InstrumentationClassLoader cl;
      if (null == (cl = instrumentationClassLoader.get())) {
        synchronized (instrumentationClassLoaderLock) {
          if (null == (cl = instrumentationClassLoader.get())) {
            // previous instance was unloaded, create fresh one
            cl = new InstrumentationClassLoader(this);
            instrumentationClassLoader = new WeakReference<>(cl);
          }
        }
      }
      return cl.loadInstrumentationClass(name, agentCodeSource);
    } else if (name.startsWith("com.kenai.jffi")) {
      // prefer our embedded JFFI to other versions exposed by the parent class-loader
      return loadLocalClass(name, resolve);
    } else {
      return super.loadClass(name, resolve);
    }
  }

  /** Same as {@link #loadClass(String, boolean)} but it doesn't delegate to the parent. */
  private Class<?> loadLocalClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
      Class<?> c = findLoadedClass(name);
      if (null == c) {
        c = findClass(name);
      }
      if (resolve) {
        resolveClass(c);
      }
      return c;
    }
  }

  @Override
  protected Class<?> findClass(String name) throws ClassNotFoundException {
    byte[] buf = loadClassBytes(name);
    return defineClass(name, buf, 0, buf.length, agentCodeSource);
  }

  byte[] loadClassBytes(String name) throws ClassNotFoundException {
    String entryName = agentJarIndex.classEntryName(name);
    if (null != entryName) {
      JarEntry jarEntry = agentJarFile.getJarEntry(entryName);
      if (null != jarEntry) {
        byte[] buf = new byte[(int) jarEntry.getSize()];
        try (InputStream in = agentJarFile.getInputStream(jarEntry)) {
          int bytesRead = in.read(buf);
          while (bytesRead < buf.length) {
            int delta = in.read(buf, bytesRead, buf.length - bytesRead);
            if (delta < 0) {
              break;
            }
            bytesRead += delta;
          }
          if (bytesRead == buf.length) {
            return buf;
          } else {
            log.warn("Malformed class data at {}", jarEntry);
          }
        } catch (IOException e) {
          log.warn("Problem reading class data at {}", jarEntry, e);
        }
      }
    }
    throw new ClassNotFoundException(name);
  }

  @Override
  protected Package getPackage(String name) {
    synchronized (definedPackages) {
      if (definedPackages.add(name)) {
        return definePackage(name, null, null, null, null, null, null, null);
      } else {
        return super.getPackage(name);
      }
    }
  }

  @Override
  public String toString() {
    return "datadog";
  }
}
