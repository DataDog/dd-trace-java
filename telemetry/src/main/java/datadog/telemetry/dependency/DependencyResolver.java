package datadog.telemetry.dependency;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DependencyResolver {

  private static final Logger log = LoggerFactory.getLogger(DependencyResolver.class);
  private static final String JAR_SUFFIX = ".jar";

  public static List<Dependency> resolve(URI uri) {
    final String scheme = uri.getScheme();
    try {
      if ("file".equals(scheme)) {
        File f;
        if (uri.isOpaque()) {
          f = new File(uri.getSchemeSpecificPart());
        } else {
          f = new File(uri);
        }
        return extractFromFile(f);
      } else if ("jar".equals(scheme)) {
        return extractFromJarURI(uri);
      }
    } catch (Throwable t) {
      log.debug("Failed to determine dependency for uri {}", uri, t);
    }
    return Collections.emptyList();
  }

  private static List<Dependency> extractFromFile(final File jar) throws IOException {
    if (!jar.exists()) {
      log.debug("unable to find dependency {} (path does not exist)", jar);
      return Collections.emptyList();
    } else if (!jar.getName().endsWith(JAR_SUFFIX)) {
      log.debug("unsupported file dependency type : {}", jar);
      return Collections.emptyList();
    }

    try (final JarFile file = new JarFile(jar, false /* no verify */);
        final InputStream is = new FileInputStream(jar)) {
      return extractFromJarFile(file, is);
    }
  }

  /* for jar urls as handled by spring boot */
  private static List<Dependency> extractFromJarURI(final URI uri) throws IOException {
    final URL url = uri.toURL();
    final JarURLConnection conn = (JarURLConnection) url.openConnection();
    // Prevent sharing of jar file handles, which can break Spring Boot's loader.
    // https://github.com/DataDog/dd-trace-java/issues/6704
    conn.setUseCaches(false);
    try (final JarFile jar = conn.getJarFile();
        final InputStream is = conn.getInputStream()) {
      return extractFromJarFile(jar, is);
    }
  }

  private static List<Dependency> extractFromJarFile(final JarFile jar, final InputStream is)
      throws IOException {
    // Try Maven's pom.properties
    final List<Dependency> deps = Dependency.fromMavenPom(jar);
    if (!deps.isEmpty()) {
      return deps;
    }
    // Try manifest or file name
    final Manifest manifest = jar.getManifest();
    return Collections.singletonList(Dependency.guessFallbackNoPom(manifest, jar.getName(), is));
  }
}
