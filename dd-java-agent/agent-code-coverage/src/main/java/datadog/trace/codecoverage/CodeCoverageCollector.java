package datadog.trace.codecoverage;

import datadog.trace.coverage.CoverageKey;
import datadog.trace.coverage.LinesCoverage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically collects code coverage probe data, resolves it to covered source lines using a
 * cached probe-to-line mapping, and sends the results via a {@link CodeCoverageSender}.
 *
 * <p>On the first collection cycle (or when new classes appear), a classpath scan builds the
 * cache. Subsequent cycles simply iterate boolean probe arrays and set bits -- no JaCoCo {@code
 * Analyzer} pass is needed.
 */
public final class CodeCoverageCollector {

  private static final Logger log = LoggerFactory.getLogger(CodeCoverageCollector.class);

  private final CodeCoverageTransformer transformer;
  private final CodeCoverageSender sender;
  private final int intervalSeconds;
  private final String explicitClasspath;
  private final ProbeMappingCache probeCache = new ProbeMappingCache();
  private volatile ScheduledExecutorService scheduler;

  /**
   * @param transformer the transformer that holds runtime probe data
   * @param sender the sender to deliver coverage results to
   * @param intervalSeconds interval between collection cycles
   * @param explicitClasspath explicit classpath override (nullable; if null, auto-detected)
   */
  public CodeCoverageCollector(
      CodeCoverageTransformer transformer,
      CodeCoverageSender sender,
      int intervalSeconds,
      String explicitClasspath) {
    this.transformer = transformer;
    this.sender = sender;
    this.intervalSeconds = intervalSeconds;
    this.explicitClasspath = explicitClasspath;
  }

  /** Starts the periodic collection scheduler. */
  public void start() {
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "dd-code-coverage");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleAtFixedRate(this::collect, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    log.debug(
        "Code coverage collector started with interval of {} seconds",
        intervalSeconds);
  }

  /** Stops the periodic collection scheduler. */
  public void stop() {
    ScheduledExecutorService s = scheduler;
    if (s != null) {
      s.shutdownNow();
    }
  }

  /** Performs a single collection cycle: collect probes, resolve via cache, and send. */
  void collect() {
    try {
      // 1. Collect and reset probes
      ExecutionDataStore execStore = new ExecutionDataStore();
      SessionInfoStore sessionStore = new SessionInfoStore();
      transformer.collectAndReset(execStore, sessionStore);

      // 2. Separate cache hits from misses
      Collection<ExecutionData> allEntries = execStore.getContents();
      List<ExecutionData> cacheMisses = new ArrayList<>();
      for (ExecutionData ed : allEntries) {
        if (probeCache.get(ed.getId()) == null) {
          cacheMisses.add(ed);
        }
      }

      // 3. Build cache entries for misses (scans classpath)
      if (!cacheMisses.isEmpty()) {
        List<File> classpathEntries = resolveClasspath();
        probeCache.buildMissing(cacheMisses, classpathEntries);
        log.debug("Built cache entries for {} new classes", cacheMisses.size());
      }

      // 4. Build coverage from cache
      Map<CoverageKey, LinesCoverage> coverage = new HashMap<>();
      for (ExecutionData ed : allEntries) {
        ClassProbeMapping mapping = probeCache.get(ed.getId());
        if (mapping == null || mapping.className == null) {
          continue; // no mapping available
        }

        CoverageKey key = new CoverageKey(mapping.sourceFile, mapping.className);
        LinesCoverage lc = coverage.computeIfAbsent(key, k -> new LinesCoverage());
        lc.executableLines.or(mapping.executableLines);

        boolean[] probes = ed.getProbes();
        for (int p = 0; p < probes.length && p < mapping.probeToLines.length; p++) {
          if (probes[p]) {
            for (int line : mapping.probeToLines[p]) {
              lc.coveredLines.set(line);
            }
          }
        }
      }

      // 5. Send if there is data
      if (!coverage.isEmpty()) {
        sender.upload(coverage);
      }
    } catch (Exception e) {
      log.debug("Error during code coverage collection", e);
    }
  }

  /**
   * Resolves classpath entries to analyze. If an explicit classpath is configured, it takes
   * precedence. Otherwise, falls back to {@code java.class.path} system property.
   */
  private List<File> resolveClasspath() {
    String cp;
    if (explicitClasspath != null && !explicitClasspath.isEmpty()) {
      cp = explicitClasspath;
    } else {
      cp = System.getProperty("java.class.path");
    }
    List<File> entries = new ArrayList<>();
    if (cp != null && !cp.isEmpty()) {
      for (String path : cp.split(File.pathSeparator)) {
        String trimmed = path.trim();
        if (!trimmed.isEmpty()) {
          entries.add(new File(trimmed));
        }
      }
    }
    return entries;
  }
}
