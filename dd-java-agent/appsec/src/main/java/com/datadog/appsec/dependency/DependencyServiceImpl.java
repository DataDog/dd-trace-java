package com.datadog.appsec.dependency;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.JarURLConnection;
import java.net.URI;
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

  private Set<URI> currentSet; // guarded by this

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
    Set<URI> currentSet;
    synchronized (this) {
      currentSet = this.currentSet;
      this.currentSet = new HashSet<>();
    }

    List<Dependency> deps = new ArrayList<>(currentSet.size());
    for (URI uri : currentSet) {
      if (Thread.interrupted()) {
        log.warn("Interrupted while processing dependencies");
        break;
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
  public void addURI(URI uri) {
    // we ignore .class files directly within webapp folder (they aren't part of dependencies)
    String path = uri.getPath();
    if (path != null && path.endsWith(".class")) {
      return;
    }

    synchronized (this) {
      currentSet.add(uri);
    }
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
    try (JarFile file = new JarFile(jar);
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
}
