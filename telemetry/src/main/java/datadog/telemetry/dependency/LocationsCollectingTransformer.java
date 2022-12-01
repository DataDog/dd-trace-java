package datadog.telemetry.dependency;

import datadog.trace.agent.tooling.WeakCaches;
import datadog.trace.bootstrap.WeakCache;
import java.lang.instrument.ClassFileTransformer;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LocationsCollectingTransformer implements ClassFileTransformer {
  private static final Logger log = LoggerFactory.getLogger(LocationsCollectingTransformer.class);

  private static final int MAX_CACHED_JARS = 4096;
  private final DependencyServiceImpl dependencyService;
  private final WeakCache<ProtectionDomain, Boolean> seenDomains =
      WeakCaches.newWeakCache(MAX_CACHED_JARS);

  public LocationsCollectingTransformer(DependencyServiceImpl dependencyService) {
    this.dependencyService = dependencyService;
    seenDomains.put(LocationsCollectingTransformer.class.getProtectionDomain(), true);
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
    log.debug("Saw new protection domain: {}", domain);
    final CodeSource codeSource = domain.getCodeSource();
    if (null != codeSource) {
      final URL location = codeSource.getLocation();
      if (null != location) {
        dependencyService.addURL(location);
      }
    }
    return true;
  }
}
