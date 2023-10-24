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

  public static List<Dependency> resolve(final DependencyPath dependencyPath) {
    return extractDependencies(dependencyPath);
  }

  // package private for testing
  static List<Dependency> extractDependencies(final DependencyPath dependencyPath) {
    List<Dependency> dependencies = Collections.emptyList();
    try {
      if (DependencyPath.Type.JAR.equals(dependencyPath.type)) {
        dependencies = extractDependenciesFromJar(new File(dependencyPath.location));
      } else if (dependencyPath.isInJar) {
        Dependency dependency = getNestedDependency(dependencyPath.location);
        if (dependency != null) {
          dependencies = Collections.singletonList(dependency);
        }
      }
    } catch (RuntimeException rte) {
      log.debug("Failed to determine dependency for {}", dependencyPath.location, rte);
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
  static Dependency getNestedDependency(String location) {
    String lastPart = null;
    String fileName = null;
    try {
      URL url = new URL(location);
      JarURLConnection jarConnection = (JarURLConnection) url.openConnection();
      Manifest manifest = jarConnection.getManifest();

      JarFile jarFile = jarConnection.getJarFile();

      // the !/ separator is hardcoded into JarURLConnection class
      String jarFileName = jarFile.getName();
      int posSep = jarFileName.indexOf("!/");
      if (posSep == -1) {
        log.debug("Unable to guess nested dependency for uri '{}': '!/' not found", location);
        return null;
      }
      lastPart = jarFileName.substring(posSep + 1);
      fileName = lastPart.substring(lastPart.lastIndexOf("/") + 1);

      return Dependency.guessFallbackNoPom(manifest, fileName, jarConnection.getInputStream());
    } catch (Exception e) {
      log.debug("unable to open nested jar manifest for {}", location, e);
    }
    log.debug(
        "Unable to guess nested dependency for uri '{}', lastPart: '{}', fileName: '{}'",
        location,
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
