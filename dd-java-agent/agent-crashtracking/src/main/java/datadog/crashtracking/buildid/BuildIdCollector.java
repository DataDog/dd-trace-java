package datadog.crashtracking.buildid;

import static datadog.crashtracking.buildid.BuildInfo.EMPTY;
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

/**
 * Collects build IDs from library files asynchronously.
 *
 * <h2>Threading Model</h2>
 *
 * This class follows a single-producer, single-consumer (SPSC) threading model:
 *
 * <ul>
 *   <li><b>Producer (single-threaded):</b> The crash parsing flow calls {@link
 *       #resolveBuildId(Path)} to enqueue libraries for processing. This method is guaranteed to be
 *       called from a single thread.
 *   <li><b>Consumer (single-threaded):</b> The background {@link Collector} thread processes the
 *       work queue. Only one collector instance is ever started, enforced by {@code
 *       collecting.compareAndSet(false, true)}.
 * </ul>
 *
 * <h2>Synchronization Strategy</h2>
 *
 * <ul>
 *   <li><b>workQueue:</b> An SPSC (Single Producer Single Consumer) queue - thread-safe for one
 *       producer and one consumer without additional synchronization.
 *   <li><b>processed:</b> A plain {@link HashSet} - safe because it's only accessed from the
 *       producer thread (crash parsing flow).
 *   <li><b>libraryBuildInfo:</b> A {@link ConcurrentHashMap} - accessed from both producer
 *       (removal) and consumer (insertion) threads, requires concurrent access.
 *   <li><b>collecting:</b> An {@link AtomicBoolean} - coordinates lifecycle and ensures exactly one
 *       collector is started.
 * </ul>
 */
public class BuildIdCollector {
  static final Logger LOGGER = LoggerFactory.getLogger(BuildIdCollector.class);

  /** Thread-safe map: accessed by both producer and consumer threads. */
  private final Map<String, BuildInfo> libraryBuildInfo = new ConcurrentHashMap<>();

  /** Tracks processed filenames. Only accessed from producer thread - no synchronization needed. */
  private final Set<String> processed = new HashSet<>();

  /** Ensures exactly one collector thread is started. */
  private final AtomicBoolean collecting = new AtomicBoolean(false);

  /** SPSC queue: one producer (crash parsing), one consumer (collector thread). */
  private final MessagePassingQueue<Path> workQueue = Queues.spscArrayQueue(Short.MAX_VALUE);

  /** Signals when collection is complete. */
  private final CountDownLatch latch = new CountDownLatch(1);

  /**
   * Consumer thread that processes the work queue and extracts build IDs.
   *
   * <p><b>Threading:</b> Runs in a single background thread. Only one instance is ever created,
   * guaranteed by the {@code collecting.compareAndSet(false, true)} check in {@link
   * #resolveBuildId(Path)}.
   *
   * <p>Polls the {@code workQueue} until either:
   *
   * <ul>
   *   <li>The deadline is reached, or
   *   <li>The {@code collecting} flag is set to false (via {@link #awaitCollectionDone(int)}) and
   *       the queue is empty
   * </ul>
   */
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

  /**
   * Registers a library filename as needing build ID resolution.
   *
   * <p>Called from producer thread (crash parsing flow) before collection starts.
   *
   * @param filename the library filename to track
   */
  public void addUnprocessedLibrary(String filename) {
    if (!collecting.get()) {
      libraryBuildInfo.putIfAbsent(filename, EMPTY);
    }
  }

  /**
   * Enqueues a library path for build ID extraction.
   *
   * <p><b>Threading:</b> This method is called exclusively from the producer thread (crash parsing
   * flow). It starts the collector thread on first invocation and enqueues work items.
   *
   * <p>The {@code processed} set is only accessed here (producer thread), so no synchronization is
   * needed for it.
   *
   * @param path the path to the library file
   */
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

  /**
   * Signals that no more work will be enqueued and waits for collection to complete.
   *
   * <p>Called from producer thread to stop collection and wait for the collector to finish
   * processing the queue.
   *
   * @param timeoutSeconds maximum time to wait for collection to complete
   */
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

  /**
   * Retrieves the build information for a library.
   *
   * <p>This method can be called from any thread after collection is complete. The {@link
   * ConcurrentHashMap} ensures thread-safe reads.
   *
   * @param filename the library filename
   * @return the build information, or null if not found
   */
  public BuildInfo getBuildInfo(String filename) {
    return libraryBuildInfo.get(filename);
  }
}
