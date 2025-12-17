package datadog.telemetry.dependency;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DependencyResolver {

  private static final Logger log = LoggerFactory.getLogger(DependencyResolver.class);

  public static List<Dependency> resolve(URI uri) {
    try {
      return internalResolve(uri);
    } catch (Throwable t) {
      log.debug("Failed to determine dependency for uri {}", uri, t);
    }
    return Collections.emptyList();
  }

  static List<Dependency> internalResolve(final URI uri) throws IOException {
    final String scheme = uri.getScheme();
    JarReader.Extracted metadata = null;
    switch (scheme) {
      case "file":
        final File f = uri.isOpaque() ? new File(uri.getSchemeSpecificPart()) : new File(uri);
        final String path = f.getAbsolutePath();
        metadata = JarReader.readJarFile(path);
        break;
      case "jar":
        metadata = resolveNestedJar(uri);
        break;
      default:
    }
    if (metadata == null) {
      log.debug("unsupported dependency type: {}", uri);
      return Collections.emptyList();
    }
    if (metadata.isDirectory) {
      log.debug("Extracting dependencies from directories is not supported: {}", uri);
      return Collections.emptyList();
    }
    final List<Dependency> dependencies =
        Dependency.fromMavenPom(metadata.jarName, metadata.pomProperties);
    if (!dependencies.isEmpty()) {
      return dependencies;
    }
    try (final InputStream is = metadata.inputStreamSupplier.get()) {
      return Collections.singletonList(
          Dependency.guessFallbackNoPom(metadata.manifest, metadata.jarName, is));
    }
  }

  private static JarReader.Extracted resolveNestedJar(final URI uri) throws IOException {
    String path = uri.getSchemeSpecificPart();

    // Strip optional trailing '!' or '!/'.
    if (path.endsWith("!")) {
      path = path.substring(0, path.length() - 1);
    } else if (path.endsWith("!/")) {
      path = path.substring(0, path.length() - 2);
    }

    if (path.startsWith("file:")) {
      // Old style nested dependencies, as seen in Spring Boot 2 and others.
      // These look like jar:file:/path/to.jar!/path/to/nested.jar!/
      path = path.substring("file:".length());
      final int sepIdx = path.indexOf("!/");
      if (sepIdx == -1) {
        // JBoss may use the "jar:file" format to reference jar files instead of nested jars.
        // These look like: jar:file:/path/to.jar!/
        return JarReader.readJarFile(path);
      }
      final String outerPath = path.substring(0, sepIdx);
      final String innerPath = path.substring(sepIdx + 2);
      return JarReader.readNestedJarFile(outerPath, innerPath);
    } else if (path.startsWith("nested:")) {
      // New style nested dependencies, for Spring 3.2+.
      // These look like jar:nested:/path/to.jar/!path/to/nested.jar!/ (yes, /!, not !/).
      // See https://docs.spring.io/spring-boot/specification/executable-jar/jarfile-class.html
      path = path.substring("nested:".length());
      final int sepIdx = path.indexOf("/!");
      if (sepIdx == -1) {
        throw new IllegalArgumentException("Invalid nested jar path: " + path);
      }
      final String outerPath = path.substring(0, sepIdx);
      final String innerPath = path.substring(sepIdx + 2);
      return JarReader.readNestedJarFile(outerPath, innerPath);
    } else {
      return null;
    }
  }
}
