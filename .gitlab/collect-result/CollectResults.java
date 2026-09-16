import java.nio.file.Path;
import java.util.List;

class CollectResults {
  public static void main(String[] args) throws Exception {
    // Detect flaky jobs
    var continueOnFailure = Boolean.parseBoolean(System.getenv("CONTINUE_ON_FAILURE"));
    // Run collector
    var collector =
        new ResultCollector(
            Path.of("results"),
            Path.of("workspace"),
            List.of(Path.of("workspace"), Path.of("buildSrc")),
            continueOnFailure);
    collector.collect();
  }
}
