package datadog.telemetry.dependency;

import java.net.URI;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DependencyResolverQueue {

  private static final Logger log = LoggerFactory.getLogger(DependencyResolverQueue.class);

  private final Queue<URI> newUrlsQueue;
  private final Set<URI> processedUrlsSet; // guarded by this

  public DependencyResolverQueue() {
    this.newUrlsQueue = new ConcurrentLinkedQueue<>();
    this.processedUrlsSet = new HashSet<>();
  }

  public void queueURI(URI uri) {
    if (uri == null) {
      return;
    }

    // we ignore .class files directly within webapp folder (they aren't part of dependencies)
    String path = uri.getPath();
    if (path != null && path.endsWith(".class")) {
      return;
    }

    // ignore already processed url
    synchronized (this) {
      if (processedUrlsSet.contains(uri)) {
        return;
      }
    }

    newUrlsQueue.add(uri);
  }

  public Dependency pollDependency() {
    URI uri = newUrlsQueue.poll();

    // no new deps
    if (uri == null) {
      return null;
    }

    Dependency dep = DependencyResolver.resolve(uri);
    if (dep == null) {
      if ("jrt".equals(uri.getScheme()) || "x-internal-jar".equals(uri.getScheme())) {
        log.debug("unable to detect dependency for URI {}", uri);
      } else {
        log.warn("unable to detect dependency for URI {}", uri);
      }
      return null;
    }
    if (log.isDebugEnabled()) {
      log.debug("dependency detected {} for {}", dep, uri);
    }

    synchronized (this) {
      processedUrlsSet.add(uri);
    }

    return dep;
  }
}
