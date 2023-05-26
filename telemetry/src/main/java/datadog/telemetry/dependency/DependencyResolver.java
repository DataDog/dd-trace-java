package datadog.telemetry.dependency;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DependencyResolver {

  private static final Logger log = LoggerFactory.getLogger(DependencyResolver.class);
  private static final String JAR_SUFFIX = ".jar";

  public static List<Dependency> resolve(URI uri) {
    return extractDependenciesFromURI(uri);
  }

  /**
   * Identify library from a URI
   *
   * @param uri URI to a dependency
   * @return dependency, or null if unable to qualify jar
   */
  // package private for testing
  static List<Dependency> extractDependenciesFromURI(URI uri) {
    String scheme = uri.getScheme();
    List<Dependency> dependencies = Collections.emptyList();
    try {
      if ("file".equals(scheme)) {
        File f;
        if (uri.isOpaque()) {
          f = new File(uri.getSchemeSpecificPart());
        } else {
          f = new File(uri);
        }
        dependencies = extractDependenciesFromJar(f);
      } else if ("jar".equals(scheme)) {
        Dependency dependency = getNestedDependency(uri);
        if (dependency != null) {
          dependencies = Collections.singletonList(dependency);
        }
      }
    } catch (RuntimeException rte) {
      log.debug("Failed to determine dependency for uri {}", uri, rte);
    }
    // TODO : moving jboss vfs here is probably a idea
    // it might however require to do somme checks to make sure it's only applied to jboss
    // and not any application server that also uses vfs:// locations

    return dependencies;
  }

  /**
   * Identify a library from a .jar file
   *
   * @param jar jar dependency
   * @return detected dependency, {@code null} if unable to get dependency from jar
   */
  static List<Dependency> extractDependenciesFromJar(File jar) {
    if (!jar.exists()) {
      log.debug("unable to find dependency {} (path does not exist)", jar);
      return Collections.emptyList();
    } else if (!jar.getName().endsWith(JAR_SUFFIX)) {
      log.debug("unsupported file dependency type : {}", jar);
      return Collections.emptyList();
    }

    List<Dependency> dependencies = Collections.emptyList();
    try (JarFile file = new JarFile(jar, false /* no verify */)) {

      // Try to get from maven properties
      dependencies = Dependency.fromMavenPom(file);

      // Try to guess from manifest or file name
      if (dependencies.isEmpty()) {
        try (InputStream is = Files.newInputStream(jar.toPath())) {
          Manifest manifest = file.getManifest();
          dependencies =
              Collections.singletonList(Dependency.guessFallbackNoPom(manifest, jar.getName(), is));
        }
      }
    } catch (IOException e) {
      log.debug("unable to read jar file {}", jar, e);
    }

    return dependencies;
  }

  /* for jar urls as handled by spring boot */
  static Dependency getNestedDependency(URI uri) {
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
        log.debug("Unable to guess nested dependency for uri '{}': '!/' not found", uri);
        return null;
      }
      lastPart = jarFileName.substring(posSep + 1);
      fileName = lastPart.substring(lastPart.lastIndexOf("/") + 1);

      return Dependency.guessFallbackNoPom(manifest, fileName, jarConnection.getInputStream());
    } catch (IOException e) {
      log.debug("unable to open nested jar manifest for {}", uri, e);
    }
    log.debug(
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
