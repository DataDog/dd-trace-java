import java.nio.file.Path;
import java.util.List;

class CollectResults {
  private static final int COUNTS_FAILED = 2;

  public static void main(String[] args) throws Exception {
    boolean countTests = args.length == 2 && "--counts".equals(args[0]);
    if (args.length != 0 && !countTests) {
      throw new IllegalArgumentException("Usage: CollectResults.java [--counts RESULTS_DIR]");
    }
    // Detect flaky jobs
    var continueOnFailure = Boolean.parseBoolean(System.getenv("CONTINUE_ON_FAILURE"));
    // Run collector
    var collector =
        new ResultCollector(
            Path.of(countTests ? args[1] : "results"),
            Path.of("workspace"),
            List.of(Path.of("workspace"), Path.of("buildSrc"), Path.of("build-logic")),
            continueOnFailure);
    var countsByTarget = collector.collect(countTests);
    if (countTests) {
      try {
        System.out.println(collector.countTests(countsByTarget).asLine());
      } catch (Exception e) {
        System.err.println("Could not count exported JUnit reports");
        e.printStackTrace(System.err);
        System.exit(COUNTS_FAILED);
      }
    }
  }
}
