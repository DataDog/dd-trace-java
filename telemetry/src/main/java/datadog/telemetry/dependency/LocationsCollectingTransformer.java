package datadog.telemetry.dependency;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.lang.instrument.ClassFileTransformer;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
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
      dependencyService.addURL(location);
    }
    return true;
  }
}
