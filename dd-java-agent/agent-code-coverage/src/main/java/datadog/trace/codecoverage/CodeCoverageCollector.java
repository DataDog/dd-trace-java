package datadog.trace.codecoverage;

import datadog.trace.coverage.LinesCoverage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically collects code coverage probe data, resolves it to covered source lines using
 * JaCoCo's analysis pipeline, and sends the results via a {@link CodeCoverageLcovSender}.
 */
public final class CodeCoverageCollector {

  private static final Logger log = LoggerFactory.getLogger(CodeCoverageCollector.class);

  private final CodeCoverageTransformer transformer;
  private final CodeCoverageLcovSender sender;
  private final int intervalSeconds;
  private final String explicitClasspath;
  private volatile ScheduledExecutorService scheduler;

  /**
   * @param transformer the transformer that holds runtime probe data
   * @param sender the sender to deliver coverage results to
   * @param intervalSeconds interval between collection cycles
   * @param explicitClasspath explicit classpath override (nullable; if null, auto-detected)
   */
  public CodeCoverageCollector(
      CodeCoverageTransformer transformer,
      CodeCoverageLcovSender sender,
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

  /** Performs a single collection cycle: collect probes, analyze, and send. */
  void collect() {
    try {
      // 1. Collect and reset probes
      ExecutionDataStore execStore = new ExecutionDataStore();
      SessionInfoStore sessionStore = new SessionInfoStore();
      transformer.collectAndReset(execStore, sessionStore);

      // 2. Resolve classpath entries
      List<File> classpathEntries = resolveClasspath();

      // 3. Analyze: map probes to source lines using original class files
      CoverageBuilder builder = new CoverageBuilder();
      Analyzer analyzer = new Analyzer(execStore, builder);
      for (File entry : classpathEntries) {
        if (entry.exists()) {
          try {
            analyzer.analyzeAll(entry);
          } catch (IOException e) {
            log.debug("Failed to analyze classpath entry: {}", entry, e);
          }
        }
      }

      // 4. Build coverage map: source file -> lines coverage
      Map<String, LinesCoverage> coverage = new HashMap<>();
      for (IClassCoverage cc : builder.getClasses()) {
        if (cc.getSourceFileName() == null) {
          continue;
        }
        String sourceFile = cc.getPackageName() + "/" + cc.getSourceFileName();
        LinesCoverage lc = coverage.computeIfAbsent(sourceFile, k -> new LinesCoverage());
        for (int line = cc.getFirstLine(); line <= cc.getLastLine(); line++) {
          int status = cc.getLine(line).getStatus();
          if (status != ICounter.EMPTY) {
            lc.executableLines.set(line);
            if (status == ICounter.PARTLY_COVERED || status == ICounter.FULLY_COVERED) {
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
