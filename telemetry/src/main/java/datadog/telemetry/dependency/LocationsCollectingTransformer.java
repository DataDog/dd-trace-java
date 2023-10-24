package datadog.telemetry.dependency;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Field;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.zip.ZipFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LocationsCollectingTransformer implements ClassFileTransformer {
  private static final Logger log = LoggerFactory.getLogger(LocationsCollectingTransformer.class);

  private static final int MAX_CACHED_JARS = 1024;
  private final DependencyService dependencyService;
  private final DDCache<ProtectionDomain, Boolean> seenDomains =
      DDCaches.newFixedSizeWeakKeyCache(MAX_CACHED_JARS);

  public LocationsCollectingTransformer(DependencyService dependencyService) {
    this.dependencyService = dependencyService;
  }

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer) {
    if (protectionDomain != null) {
      seenDomains.computeIfAbsent(protectionDomain, this::addDependency);
    }
    // returning 'null' is the best way to indicate that no transformation has been done.
    return null;
  }

  private boolean addDependency(final ProtectionDomain domain) {
    final CodeSource codeSource = domain.getCodeSource();
    final URL location = codeSource != null ? codeSource.getLocation() : null;
    final ClassLoader classLoader = domain.getClassLoader();
    log.debug("New protection domain with location {} and class loader {}", location, classLoader);
    if (location != null) {
      dependencyService.add(DependencyPath.forURL(location));
      return true;
    }
    findKnopflerfishUrl(classLoader);
    return true;
  }

  private boolean findKnopflerfishUrl(final ClassLoader classLoader) {
    if (classLoader == null) {
      return false;
    }
    final Class<?> clazz = classLoader.getClass();
    if (!"org.knopflerfish.framework.BundleClassLoader".equals(clazz.getName())) {
      return false;
    }
    log.debug("FOUND KNOPFLERFISH CLASSLOADER: {}", classLoader);
    try {
      final Field classPathField = clazz.getDeclaredField("classPath");
      classPathField.setAccessible(true);
      final Object classPathObj = classPathField.get(classLoader);
      final Class<?> classPathClazz = classPathObj.getClass();
      final Field archivesField = classPathClazz.getDeclaredField("archives");
      archivesField.setAccessible(true);
      final List<?> archivesObj = (List<?>) archivesField.get(classPathObj);
      for (final Object archiveObj : archivesObj) {
        log.debug("Bundle archive class: {}", archiveObj.getClass());
        final Class<?> archiveClazz = archiveObj.getClass();
        if (!"org.knopflerfish.framework.bundlestorage.file.Archive".equals(archiveClazz.getName())) {
          continue;
        }
        final Field jarField = archiveClazz.getDeclaredField("jar");
        jarField.setAccessible(true);
        final ZipFile file = (ZipFile) jarField.get(archiveObj);
        log.debug("Adding dep: {}", file.getName());
        dependencyService.add(new DependencyPath(DependencyPath.Type.JAR, file.getName(), false));
      }
    } catch (Exception ex) {
      log.debug("Extracting jar location from Knopflerfish class loader failed", ex);
      return false;
    }
    return true;
  }
}
