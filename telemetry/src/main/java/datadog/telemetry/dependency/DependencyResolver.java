package datadog.telemetry.dependency;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DependencyResolver {

  private static final Logger log = LoggerFactory.getLogger(DependencyResolver.class);
  private static final String JAR_SUFFIX = ".jar";

  public static List<Dependency> resolve(URI uri) {
    final String scheme = uri.getScheme();
    try {
      JarReader.Extracted metadata = null;
      String path = null;
      if ("file".equals(scheme)) {
        File f;
        if (uri.isOpaque()) {
          f = new File(uri.getSchemeSpecificPart());
        } else {
          f = new File(uri);
        }
        path = f.getAbsolutePath();
        metadata = JarReader.readJarFile(path);
      } else if ("jar".equals(scheme) && uri.getSchemeSpecificPart().startsWith("file:")) {
        path = uri.getSchemeSpecificPart().substring("file:".length());
        metadata = JarReader.readNestedJarFile(path);
      } else {
        log.debug("unsupported dependency type: {}", uri);
        return Collections.emptyList();
      }
      final List<Dependency> dependencies =
          Dependency.fromMavenPom(metadata.jarName, metadata.pomProperties);
      if (!dependencies.isEmpty()) {
        return dependencies;
      }
      try (final InputStream is = new FileInputStream(path)) {
        return Collections.singletonList(
            Dependency.guessFallbackNoPom(metadata.manifest, metadata.jarName, is));
      }
    } catch (Throwable t) {
      log.debug("Failed to determine dependency for uri {}", uri, t);
    }
    return Collections.emptyList();
  }
}
