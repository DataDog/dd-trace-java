package datadog.telemetry.dependency;

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

  private final Queue<DependencyPath> queue;
  private final Set<String> processedLocations;

  public DependencyResolverQueue() {
    queue = new ConcurrentLinkedQueue<>();
    processedLocations = new HashSet<>();
  }

  public void add(final DependencyPath dependencyPath) {
    if (dependencyPath == null || dependencyPath.location == null) {
      return;
    }
    synchronized (this) {
      if (!processedLocations.add(dependencyPath.location)) {
        return;
      }
    }
    queue.add(dependencyPath);
  }

  public List<Dependency> pollDependency() {
    final DependencyPath dependencyPath = queue.poll();

    // no new deps
    if (dependencyPath == null) {
      return Collections.emptyList();
    }

    List<Dependency> dep = DependencyResolver.resolve(dependencyPath);
    if (dep.isEmpty()) {
      log.debug("unable to detect dependency for path {}", dependencyPath.location);
      return Collections.emptyList();
    }
    if (log.isDebugEnabled()) {
      log.debug("dependencies detected {} for {}", dep, dependencyPath.location);
    }

    return dep;
  }
}
