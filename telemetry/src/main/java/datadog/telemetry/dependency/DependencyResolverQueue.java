package datadog.telemetry.dependency;

import datadog.trace.api.Config;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DependencyResolverQueue {

  private static final Logger log = LoggerFactory.getLogger(DependencyResolverQueue.class);

  private final Queue<URI> newUrlsQueue;
  private final Set<URI> processedUrlsSet; // guarded by this
  private static int MAX_QUEUE_SIZE = Config.get().getTelemetryDependencyResolutionQueueSize();

  public DependencyResolverQueue() {
    newUrlsQueue = new ConcurrentLinkedQueue<>();
    processedUrlsSet = new HashSet<>();
  }

  // This constructor is intended for testing purposes only
  public DependencyResolverQueue(int maxQueueSize) {
    MAX_QUEUE_SIZE = maxQueueSize;
    newUrlsQueue = new ConcurrentLinkedQueue<>();
    processedUrlsSet = new HashSet<>();
  }

  public void queueURI(URI uri) {
    if (uri == null) {
      return;
    }
    if (processedUrlsSet.size() >= MAX_QUEUE_SIZE || newUrlsQueue.size() >= MAX_QUEUE_SIZE) {
      log.debug("DependencyResolverQueue is full, URI {} will not be queued", uri);
      return;
    }
    // we ignore .class files directly within webapp folder (they aren't part of dependencies)
    String path = uri.getPath();
    if (path != null && path.endsWith(".class")) {
      return;
    }

    // ignore already processed url
    synchronized (this) {
      if (!processedUrlsSet.add(uri)) {
        return;
      }
    }

    newUrlsQueue.add(uri);
  }

  public List<Dependency> pollDependency() {
    URI uri = newUrlsQueue.poll();

    // no new deps
    if (uri == null) {
      return Collections.emptyList();
    }

    List<Dependency> dep = DependencyResolver.resolve(uri);
    if (dep.isEmpty()) {
      log.debug("unable to detect dependency for URI {}", uri);
      return Collections.emptyList();
    }
    if (log.isDebugEnabled()) {
      log.debug("dependency detected {} for {}", dep, uri);
    }

    return dep;
  }
}
