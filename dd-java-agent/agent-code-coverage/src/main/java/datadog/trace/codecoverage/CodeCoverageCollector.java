package datadog.trace.codecoverage;

import static datadog.trace.util.AgentThreadFactory.AgentThread.CODE_COVERAGE;

import datadog.trace.coverage.CoverageKey;
import datadog.trace.coverage.LinesCoverage;
import datadog.trace.util.AgentTaskScheduler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically collects code coverage probe data, resolves it to covered source lines using a
 * cached probe-to-line mapping, and sends the results via a {@link CodeCoverageSender}.
 *
 * <p>On the first collection cycle (or when new classes appear), cache entries are built by
 * resolving class bytes through the defining ClassLoader recorded at transform time. Subsequent
 * cycles simply iterate boolean probe arrays and set bits -- no JaCoCo {@code Analyzer} pass is
 * needed.
 *
 * <p>Newly instrumented classes that have not yet received any probe hits are also reported (with
 * executable lines but empty covered lines) so the backend can compute accurate total coverage.
 */
public final class CodeCoverageCollector {

  private static final Logger log = LoggerFactory.getLogger(CodeCoverageCollector.class);

  private final BiConsumer<ExecutionDataStore, SessionInfoStore> collectAndResetFn;
  private final Supplier<List<CodeCoverageTransformer.NewClass>> drainNewClassesFn;
  private final LongFunction<ClassLoader> classLoaderLookup;
  private final Consumer<Map<CoverageKey, LinesCoverage>> uploadFn;
  private final int intervalSeconds;
  private final ProbeMappingCache probeCache = new ProbeMappingCache();
  private final AgentTaskScheduler scheduler = new AgentTaskScheduler(CODE_COVERAGE);

  /**
   * @param transformer the transformer that holds runtime probe data and classloader origins
   * @param sender the sender to deliver coverage results to
   * @param intervalSeconds interval between collection cycles
   */
  public CodeCoverageCollector(
      CodeCoverageTransformer transformer, CodeCoverageSender sender, int intervalSeconds) {
    this(
        transformer::collectAndReset,
        transformer::drainNewClasses,
        transformer::getDefiningClassLoader,
        sender::upload,
        intervalSeconds);
  }

  /** Package-private constructor for testing. */
  CodeCoverageCollector(
      BiConsumer<ExecutionDataStore, SessionInfoStore> collectAndResetFn,
      Supplier<List<CodeCoverageTransformer.NewClass>> drainNewClassesFn,
      LongFunction<ClassLoader> classLoaderLookup,
      Consumer<Map<CoverageKey, LinesCoverage>> uploadFn,
      int intervalSeconds) {
    this.collectAndResetFn = collectAndResetFn;
    this.drainNewClassesFn = drainNewClassesFn;
    this.classLoaderLookup = classLoaderLookup;
    this.uploadFn = uploadFn;
    this.intervalSeconds = intervalSeconds;
  }

  /** Starts the periodic collection scheduler. */
  public void start() {
    scheduler.scheduleAtFixedRate(
        this::collect, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    log.debug("Code coverage collector started with interval of {} seconds", intervalSeconds);
  }

  /** Stops the periodic collection scheduler. */
  public void stop() {
    scheduler.shutdown(5, TimeUnit.SECONDS);
  }

  /** Performs a single collection cycle: collect probes, resolve via cache, and send. */
  void collect() {
    try {
      // 1. Collect and reset probes
      ExecutionDataStore execStore = new ExecutionDataStore();
      SessionInfoStore sessionStore = new SessionInfoStore();
      collectAndResetFn.accept(execStore, sessionStore);

      // 2. Separate cache hits from misses
      Collection<ExecutionData> allEntries = execStore.getContents();
      List<ExecutionData> cacheMisses = new ArrayList<>();
      for (ExecutionData ed : allEntries) {
        if (probeCache.get(ed.getId()) == null) {
          cacheMisses.add(ed);
        }
      }

      // 3. Build cache entries for misses via recorded classloader
      if (!cacheMisses.isEmpty()) {
        probeCache.buildMissing(cacheMisses, classLoaderLookup);
        log.debug("Built cache entries for {} new classes", cacheMisses.size());
      }

      // 4. Build coverage from hit data
      Map<CoverageKey, LinesCoverage> coverage = new HashMap<>();
      Set<String> hitClassNames = new HashSet<>();
      for (ExecutionData ed : allEntries) {
        hitClassNames.add(ed.getName());
        ClassProbeMapping mapping = probeCache.get(ed.getId());
        if (mapping == null || mapping.className == null || mapping.sourceFile == null) {
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

      // 5. Report newly instrumented classes that had no hits this cycle
      List<CodeCoverageTransformer.NewClass> newClasses = drainNewClassesFn.get();
      for (CodeCoverageTransformer.NewClass nc : newClasses) {
        if (hitClassNames.contains(nc.className)) {
          continue; // already covered by hit data above
        }
        byte[] classBytes =
            ProbeMappingCache.resolveClassBytes(nc.className, nc.classId, classLoaderLookup);
        if (classBytes == null) {
          continue;
        }
        ClassProbeMapping mapping =
            ClassProbeMappingBuilder.buildBaseline(nc.classId, nc.className, classBytes);
        if (mapping == null || mapping.sourceFile == null) {
          continue;
        }
        CoverageKey key = new CoverageKey(mapping.sourceFile, mapping.className);
        if (!coverage.containsKey(key)) {
          LinesCoverage lc = new LinesCoverage();
          lc.executableLines.or(mapping.executableLines);
          coverage.put(key, lc);
        }
      }

      // 6. Send if there is data
      if (!coverage.isEmpty()) {
        uploadFn.accept(coverage);
      }
    } catch (Exception e) {
      log.debug("Error during code coverage collection", e);
    }
  }
}
