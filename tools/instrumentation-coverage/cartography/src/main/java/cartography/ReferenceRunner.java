package cartography;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Run the original selected upstream test classes, with unchanged assertions. */
public final class ReferenceRunner {
  public static void main(String[] args) throws Exception {
    Map<String, Object> outcomes = new LinkedHashMap<>();
    long failures = 0;
    int repeats = Integer.getInteger("cartography.repeats", 3);
    for (int r = 0; r < repeats; r++) {
      Recorder.repetition = r;
      var builder = LauncherDiscoveryRequestBuilder.request();
      for (String name : Files.readAllLines(Path.of(System.getProperty("cartography.tests"))))
        builder.selectors(DiscoverySelectors.selectClass(name));
      var listener = new SummaryGeneratingListener();
      LauncherFactory.create().execute(builder.build(), listener);
      var s = listener.getSummary();
      failures += s.getTestsFailedCount() + s.getContainersFailedCount();
      outcomes.put(
          "repeat-" + r,
          Map.of(
              "found",
              s.getTestsFoundCount(),
              "passed",
              s.getTestsSucceededCount(),
              "failed",
              s.getTestsFailedCount(),
              "skipped",
              s.getTestsSkippedCount(),
              "containerFailed",
              s.getContainersFailedCount(),
              "failures",
              s.getFailures().stream()
                  .map(f -> f.getTestIdentifier().getDisplayName() + ": " + f.getException())
                  .toList()));
      s.printTo(new java.io.PrintWriter(System.out, true));
      s.printFailuresTo(new java.io.PrintWriter(System.out, true));
    }
    Recorder.save(Path.of(System.getProperty("cartography.output")), outcomes);
    if (failures > 0 || !Recorder.errors.isEmpty())
      throw new IllegalStateException("Failed reference run; see artifact");
  }
}
