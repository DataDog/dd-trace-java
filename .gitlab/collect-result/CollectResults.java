import java.nio.file.Path;
import java.util.List;

class CollectResults {
  public static void main(String[] args) throws Exception {
    var collector =
        new ResultCollector(
            Path.of("results"),
            Path.of("workspace"),
            List.of(Path.of("workspace"), Path.of("buildSrc")));

    collector.collect();
  }
}
