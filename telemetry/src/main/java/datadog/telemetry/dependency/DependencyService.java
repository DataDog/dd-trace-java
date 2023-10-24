package datadog.telemetry.dependency;

import datadog.trace.util.AgentTaskScheduler;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service that detects app dependencies from classloading by using a no-op class-file transformer
 */
public class DependencyService implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(DependencyService.class);
  // URL hashcode/equals can have side-effects, we enforce there's only file: and jar:file only.
  private final Queue<URL> resolverQueue = new ConcurrentLinkedQueue<>();
  private final Set<URL> seenUrls = new HashSet<>();

  private final BlockingQueue<Dependency> newDependencies = new LinkedBlockingQueue<>();

  private AgentTaskScheduler.Scheduled<Runnable> scheduledTask;

  public void schedulePeriodicResolution() {
    scheduledTask =
        AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
            AgentTaskScheduler.RunnableTask.INSTANCE, this, 0, 1000L, TimeUnit.MILLISECONDS);
  }

  public void resolveOneDependency() {
    final URL url = resolverQueue.poll();
    if (url == null) {
      return;
    }

    final List<Dependency> dependencies = DependencyResolver.fromURL(url);
    if (dependencies.isEmpty()) {
      log.debug("Unable to detect dependencies for {}", url);
      return;
    }

    for (final Dependency dependency : dependencies) {
      log.debug("Resolved dependency {} from {}", dependency.name, url);
      newDependencies.add(dependency);
    }
  }

  /**
   * Registers this service as a no-op class file transformer.
   *
   * @param instrumentation instrumentation instance to register on
   */
  public void installOn(Instrumentation instrumentation) {
    instrumentation.addTransformer(new LocationsCollectingTransformer(this));
  }

  public Collection<Dependency> drainDeterminedDependencies() {
    List<Dependency> list = new LinkedList<>();
    int drained = newDependencies.drainTo(list);
    if (drained > 0) {
      return list;
    }
    return Collections.emptyList();
  }

  public void add(URL url) {
    if (url == null) {
      return;
    }
    if ("vfs".equals(url.getProtocol())) {
      url = JbossVirtualFileHelper.getJbossVfsPath(url);
    }
    if (!DependencyResolver.isValidURL(url)) {
      return;
    }
    // Process each URL just once.
    synchronized (this) {
      if (!seenUrls.add(url)) {
        return;
      }
    }
    resolverQueue.add(url);
  }

  @Override
  public void run() {
    resolveOneDependency();
  }

  public void stop() {
    if (scheduledTask != null) {
      scheduledTask.cancel();
      scheduledTask = null;
    }
  }
}
