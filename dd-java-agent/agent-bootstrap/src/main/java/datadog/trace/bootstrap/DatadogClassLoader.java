package datadog.trace.bootstrap;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Provides access to Datadog internal classes. */
public final class DatadogClassLoader extends ClassLoader {
  static {
    ClassLoader.registerAsParallelCapable();
  }

  private static final Logger log = LoggerFactory.getLogger(DatadogClassLoader.class);

  private final ClassLoader bootstrapProxy;

  private final AgentJarIndex agentJarIndex;
  private final JarFile agentJarFile;
  private final String agentResourcePrefix;

  public DatadogClassLoader(
      final URL agentJarLocation, final ClassLoader bootstrapProxy, final ClassLoader parent)
      throws Exception {
    super(parent);

    this.bootstrapProxy = bootstrapProxy;

    agentJarFile = new JarFile(new File(agentJarLocation.toURI()), false);
    agentJarIndex = AgentJarIndex.readIndex(agentJarFile);
    agentResourcePrefix = "jar:file:" + agentJarFile.getName() + "!/";
  }

  public ClassLoader getBootstrapProxy() {
    return bootstrapProxy;
  }

  @Override
  public URL getResource(final String name) {
    URL bootstrapResource = bootstrapProxy.getResource(name);
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
  protected Class<?> findClass(String name) throws ClassNotFoundException {
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
            return defineClass(name, buf, 0, buf.length);
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
  public String toString() {
    return "datadog";
  }
}
