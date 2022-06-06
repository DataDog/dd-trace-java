package datadog.telemetry.dependency;

import java.lang.instrument.ClassFileTransformer;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LocationsCollectingTransformer implements ClassFileTransformer {
  private static final Logger log = LoggerFactory.getLogger(LocationsCollectingTransformer.class);

  private final DependencyServiceImpl dependencyService;
  private final Set<ProtectionDomain> seenDomains =
      Collections.newSetFromMap(new IdentityHashMap<ProtectionDomain, Boolean>());

  public LocationsCollectingTransformer(DependencyServiceImpl dependencyService) {
    this.dependencyService = dependencyService;
  }

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer) {
    if (protectionDomain == null) {
      return null;
    }
    if (!seenDomains.add(protectionDomain)) {
      return null;
    }

    CodeSource codeSource = protectionDomain.getCodeSource();
    if (codeSource == null) {
      return null;
    }

    URL location = codeSource.getLocation();
    if (location == null) {
      return null;
    }

    dependencyService.addURL(location);

    // returning 'null' is the best way to indicate that no transformation has been done.
    return null;
  }
}
