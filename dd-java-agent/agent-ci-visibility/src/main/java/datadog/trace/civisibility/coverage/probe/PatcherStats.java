package datadog.trace.civisibility.coverage.probe;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.util.PidHelper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts how the transformer handled each class: patched, left without probes, or left to ASM and
 * why. The counts are written to a file named after the process id when the JVM exits. Off unless
 * {@code dd.civisibility.experiment.patcher.stats.dir} names a directory.
 */
final class PatcherStats {

  private static final String DIRECTORY =
      System.getProperty("dd.civisibility.experiment.patcher.stats.dir");

  private static final Map<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();

  static {
    if (DIRECTORY != null) {
      try {
        Runtime.getRuntime()
            .addShutdownHook(new Thread(PatcherStats::write, "dd-coverage-patcher-stats"));
      } catch (IllegalStateException e) {
        // the JVM is already shutting down
      }
    }
  }

  private PatcherStats() {}

  static void count(String outcome) {
    if (DIRECTORY != null) {
      COUNTS.computeIfAbsent(outcome, key -> new AtomicLong()).incrementAndGet();
    }
  }

  private static void write() {
    File file = new File(DIRECTORY, PidHelper.getPid() + ".txt");
    try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), UTF_8)) {
      for (Map.Entry<String, AtomicLong> entry : new TreeMap<>(COUNTS).entrySet()) {
        writer.write(entry.getValue().get() + "\t" + entry.getKey() + "\n");
      }
    } catch (IOException e) {
      // nothing to report to
    }
  }
}
