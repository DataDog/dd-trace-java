package datadog.crashtracking.buildid;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.common.queue.Queues;
import datadog.trace.util.AgentTaskScheduler;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.jctools.queues.MessagePassingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BuildIdCollector {
  static final Logger LOGGER = LoggerFactory.getLogger(BuildIdCollector.class);
  static final BuildInfo EMPTY = new BuildInfo(null, null, null);

  private final Map<String, BuildInfo> libraryBuildInfo = new ConcurrentHashMap<>();
  private final Set<String> processed = new HashSet<>();
  private final AtomicBoolean collecting = new AtomicBoolean(false);
  private final MessagePassingQueue<Path> workQueue = Queues.spscArrayQueue(Short.MAX_VALUE);
  private final CountDownLatch latch = new CountDownLatch(1);

  class Collector implements Runnable {
    private final BuildIdExtractor extractor = BuildIdExtractor.create();
    private final long deadline;

    Collector(long timeout, TimeUnit unit) {
      this.deadline = unit.toNanos(timeout) + System.nanoTime();
    }

    @Override
    public void run() {
      while (System.nanoTime() <= deadline) {
        final Path path = workQueue.poll();
        if (path == null) {
          if (!collecting.get()) {
            break;
          }
          LockSupport.parkNanos(MILLISECONDS.toNanos(50));
          continue;
        }
        final String fileName = path.getFileName().toString();
        LOGGER.debug("Resolving build id for {} against {}", fileName, path);
        final String buildId = extractor.extractBuildId(path);
        if (buildId != null) {
          LOGGER.debug("Found build id {} for library {}", buildId, fileName);
          libraryBuildInfo.put(
              fileName, new BuildInfo(buildId, extractor.buildIdType(), extractor.fileType()));
        }
      }
      latch.countDown();
    }
  }

  public void addUnprocessedLibrary(String filename) {
    if (!collecting.get()) {
      libraryBuildInfo.putIfAbsent(filename, EMPTY);
    }
  }

  public void resolveBuildId(Path path) {
    if (collecting.compareAndSet(false, true)) {
      AgentTaskScheduler.get().execute(new Collector(5, SECONDS));
    }
    final String filename = path.getFileName().toString();
    if (!processed.add(filename)) {
      return;
    }
    if (libraryBuildInfo.remove(filename) == null) {
      // the library is not present in the collected ones part of the stackframe
      LOGGER.debug(
          "Skipping build id resolution for {} as it was not added to unprocessed", filename);

    } else {
      workQueue.offer(path);
    }
  }

  public void awaitCollectionDone(final int timeoutSeconds) {
    if (!collecting.compareAndSet(true, false)) {
      return;
    }
    try {
      if (!latch.await(timeoutSeconds, SECONDS)) {
        LOGGER.warn("Build id collection incomplete.");
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      LOGGER.warn("Interrupted while waiting for build id collection to finish");
    }
  }

  public BuildInfo getBuildInfo(String filename) {
    return libraryBuildInfo.get(filename);
  }
}
