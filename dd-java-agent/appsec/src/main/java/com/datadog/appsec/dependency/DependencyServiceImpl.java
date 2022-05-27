package com.datadog.appsec.dependency;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service that detects app dependencies from classloading by using a no-op class-file transformer
 */
public class DependencyServiceImpl implements DependencyService {

  private static final Logger log = LoggerFactory.getLogger(DependencyServiceImpl.class);

  private static final String JAR_SUFFIX = ".jar";

  private Set<URL> currentSet; // guarded by this
  private volatile JbossVirtualFileHelper jbossVirtualFileHelper;

  public DependencyServiceImpl() {
    this.currentSet = new HashSet<>();
  }

  /**
   * Registers this service as a no-op class file transformer.
   *
   * @param instrumentation instrumentation instance to register on
   */
  public void installOn(Instrumentation instrumentation) {
    instrumentation.addTransformer(new LocationsCollectingTransformer(this));
  }

  @Override
  public Collection<Dependency> determineNewDependencies() {
    Set<URL> currentSet;
    synchronized (this) {
      currentSet = this.currentSet;
      this.currentSet = new HashSet<>();
    }

    List<Dependency> deps = new ArrayList<>(currentSet.size());
    for (URL url : currentSet) {
      if (Thread.interrupted()) {
        log.warn("Interrupted while processing dependencies");
        break;
      }

      URI uri = convertToURI(url);
      if (uri == null) {
        continue;
      }

      Dependency dep = identifyLibrary(uri);
      if (dep == null) {
        if ("jrt".equals(uri.getScheme()) || "x-internal-jar".equals(uri.getScheme())) {
          log.debug("unable to detect dependency for URI {}", uri);
        } else {
          log.warn("unable to detect dependency for URI {}", uri);
        }
        continue;
      }
      if (log.isDebugEnabled()) {
        log.debug("dependency detected {} for {}", dep, uri);
      }
      deps.add(dep);
    }

    return deps;
  }

  @Override
  public void addURL(URL url) {
    // we ignore .class files directly within webapp folder (they aren't part of dependencies)
    String path = url.getPath();
    if (path != null && path.endsWith(".class")) {
      return;
    }

    synchronized (this) {
      currentSet.add(url);
    }
  }

  private URI convertToURI(URL location) {
    URI uri = null;

    if (location.getProtocol().equals("vfs")) {
      // resolve jboss virtual file system
      try {
        uri = getJbossVfsPath(location);
      } catch (RuntimeException rte) {
        log.debug("Error in call to getJbossVfsPath", rte);
        return null;
      }
    }

    if (uri == null) {
      try {
        uri = location.toURI();
      } catch (URISyntaxException e) {
        log.warn("Error converting URL to URI", e);
        // silently ignored
      }
    }

    return uri;
  }

  /**
   * Identify library from a URI
   *
   * @param uri URI to a dependency
   * @return dependency, or null if unable to qualify jar
   */
  // package private for testing
  Dependency identifyLibrary(URI uri) {
    String scheme = uri.getScheme();
    Dependency dependency = null;
    try {
      if ("file".equals(scheme)) {
        dependency = identifyLibrary(new File(uri));
      } else if ("jar".equals(scheme)) {
        dependency = getNestedDependency(uri);
      }
    } catch (RuntimeException rte) {
      log.info("Failed to determine dependency for uri {}", uri, rte);
    }
    // TODO : moving jboss vfs here is probably a idea
    // it might however require to do somme checks to make sure it's only applied to jboss
    // and not any application server that also uses vfs:// locations

    return dependency;
  }

  /**
   * Identify a library from a .jar file
   *
   * @param jar jar dependency
   * @return detected dependency, {@code null} if unable to get dependency from jar
   */
  private Dependency identifyLibrary(File jar) {
    if (!jar.exists()) {
      log.warn("unable to find dependency %s", jar);
      return null;
    } else if (!jar.getName().endsWith(JAR_SUFFIX)) {
      log.debug("unsupported file dependency type : %s", jar);
      return null;
    }

    Dependency dependency = null;
    try (JarFile file = new JarFile(jar, false /* no verify */);
        InputStream is = new FileInputStream(jar)) {

      // Try to get from maven properties
      dependency = Dependency.fromMavenPom(file);

      // Try to guess from manifest or file name
      if (dependency == null) {
        Manifest manifest = file.getManifest();
        dependency = Dependency.guessFallbackNoPom(manifest, jar.getName(), is);
      }
    } catch (IOException e) {
      log.debug("unable to read jar file {}", jar, e);
    }

    return dependency;
  }

  /* for jar urls as handled by spring boot */
  Dependency getNestedDependency(URI uri) {
    String lastPart = null;
    String fileName = null;
    try {
      URL url = uri.toURL();
      JarURLConnection jarConnection = (JarURLConnection) url.openConnection();
      Manifest manifest = jarConnection.getManifest();

      JarFile jarFile = jarConnection.getJarFile();

      // the !/ separator is hardcoded into JarURLConnection class
      String jarFileName = jarFile.getName();
      int posSep = jarFileName.indexOf("!/");
      if (posSep == -1) {
        log.info("Unable to guess nested dependency for uri '{}': '!/' not found", uri);
        return null;
      }
      lastPart = jarFileName.substring(posSep + 1);
      fileName = lastPart.substring(lastPart.lastIndexOf("/") + 1);

      return Dependency.guessFallbackNoPom(manifest, fileName, jarConnection.getInputStream());
    } catch (IOException e) {
      log.debug("unable to open nested jar manifest for {}", uri, e);
    }
    log.info(
        "Unable to guess nested dependency for uri '{}', lastPart: '{}', fileName: '{}'",
        uri,
        lastPart,
        fileName);
    return null;
  }

  public static Attributes getManifestAttributes(File jarFile) {
    Manifest manifest = getJarManifest(jarFile);
    return manifest == null ? null : manifest.getMainAttributes();
  }

  /**
   * Get manifest from jar file
   *
   * @param jarFile jar file
   * @return manifest or null if none is available or unable to read it
   */
  public static Manifest getJarManifest(File jarFile) {
    try (JarFile file = new JarFile(jarFile)) {
      return file.getManifest();
    } catch (IOException e) {
      // silently ignored
    }
    return null;
  }

  static class JbossVirtualFileHelper {
    private final MethodHandle getPhysicalFile;
    private final MethodHandle getName;

    public static final JbossVirtualFileHelper FAILED_HELPER = new JbossVirtualFileHelper();

    public JbossVirtualFileHelper(ClassLoader loader) throws Exception {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      Class<?> virtualFileCls = loader.loadClass("org.jboss.vfs.VirtualFile");
      getPhysicalFile =
          lookup.findVirtual(virtualFileCls, "getPhysicalFile", MethodType.methodType(File.class));
      getName = lookup.findVirtual(virtualFileCls, "getName", MethodType.methodType(String.class));
    }

    private JbossVirtualFileHelper() {
      getPhysicalFile = null;
      getName = null;
    }

    public File getPhysicalFile(Object virtualFile) {
      try {
        return (File) getPhysicalFile.invoke(virtualFile);
      } catch (Throwable e) {
        throw new UndeclaredThrowableException(e);
      }
    }

    public String getName(Object virtualFile) {
      try {
        return (String) getName.invoke(virtualFile);
      } catch (Throwable e) {
        throw new UndeclaredThrowableException(e);
      }
    }
  }

  private URI getJbossVfsPath(URL location) {
    JbossVirtualFileHelper jbossVirtualFileHelper = this.jbossVirtualFileHelper;
    if (jbossVirtualFileHelper == JbossVirtualFileHelper.FAILED_HELPER) {
      return null;
    }

    Object virtualFile;
    try {
      virtualFile = location.openConnection().getContent();
    } catch (IOException e) {
      // silently ignored
      return null;
    }
    if (virtualFile == null) {
      return null;
    }

    if (jbossVirtualFileHelper == null) {
      try {
        jbossVirtualFileHelper =
            this.jbossVirtualFileHelper =
                new JbossVirtualFileHelper(virtualFile.getClass().getClassLoader());
      } catch (Exception e) {
        log.warn("Error preparing for inspection of jboss virtual files", e);
        return null;
      }
    }

    // call VirtualFile.getPhysicalFile
    File physicalFile = jbossVirtualFileHelper.getPhysicalFile(virtualFile);
    if (physicalFile.isFile() && physicalFile.getName().endsWith(".jar")) {
      return physicalFile.toURI();
    } else {
      log.info("Physical file {} is not a jar", physicalFile);
    }

    // not sure what this is about, but it's what the old code used to do
    // this is not correct as a general matter, since getName returns the virtual name,
    // which may not match the physical name
    // The original comment reads:
    // "physical file returns 'content' folder, we manually resolve to the actual jar location"
    String fileName = jbossVirtualFileHelper.getName(virtualFile);
    physicalFile = new File(physicalFile.getParentFile(), fileName);
    if (physicalFile.isFile()) {
      return physicalFile.toURI();
    }
    return null;
  }
}
