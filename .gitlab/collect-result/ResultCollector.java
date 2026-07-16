import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

final class ResultCollector {
  private static final int[] AGGREGATED_NAME_FIELDS_FROM_END = {5, 2, 1};

  private final Path resultsDir;
  private final Path workspaceDir;
  private final List<Path> searchDirs;
  private final boolean continueOnFailure;
  private final SourceFileResolver sourceFileResolver;

  ResultCollector(
      Path resultsDir, Path workspaceDir, List<Path> searchDirs, boolean continueOnFailure) {
    this.resultsDir = resultsDir;
    this.workspaceDir = workspaceDir;
    this.searchDirs = searchDirs;
    this.continueOnFailure = continueOnFailure;
    this.sourceFileResolver = new SourceFileResolver(workspaceDir);
  }

  void collect() throws Exception {
    Files.createDirectories(resultsDir);
    Files.createDirectories(workspaceDir);

    var testResultDirs = findTestResultDirs();
    if (testResultDirs.isEmpty()) {
      System.out.println("No test results found");
      return;
    }

    if (continueOnFailure) {
      System.out.println("CONTINUE_ON_FAILURE=true: reporting all tests as skip");
    }

    System.out.println("Saving test results:");
    for (var sourceXml : findXmlFiles(testResultDirs)) {
      collect(sourceXml);
    }
  }

  private void collect(Path sourceXml) throws Exception {
    var aggregatedName = aggregatedFileName(sourceXml);
    var targetXml = resultsDir.resolve(aggregatedName);
    System.out.print("- " + toUnixString(sourceXml) + " as " + aggregatedName);

    var sourceFile = sourceFileResolver.resolve(sourceXml);
    var report = JUnitReport.parse(sourceXml);
    var reportChangedBeforeFinalStatus = report.addFileAttribute(sourceFile);
    // Before normalization: retried attempts are matched on raw classname#name (see
    // JUnitReport#tagRetriedAttempts) so distinct tests sharing a normalized name are not collapsed.
    report.tagRetriedAttempts();
    reportChangedBeforeFinalStatus |= report.normalizeStableTestNames();
    report.tagSyntheticFailures();
    // Flaky jobs (CONTINUE_ON_FAILURE=true) never gate CI, so record every test as skip before
    // assigning natural statuses (APMLP-1267).
    if (continueOnFailure) {
      report.tagAllAsSkipped();
    }
    report.tagFinalStatuses();
    report.write(targetXml);

    if (reportChangedBeforeFinalStatus) {
      System.out.print(" (non-stable test names detected)");
    }
    System.out.println();
  }

  private List<Path> findTestResultDirs() throws IOException {
    var found = new ArrayList<Path>();
    for (var searchDir : searchDirs) {
      if (!Files.isDirectory(searchDir)) {
        continue;
      }
      try (var paths = Files.walk(searchDir)) {
        paths
            .filter(Files::isDirectory)
            .filter(path -> "test-results".equals(fileName(path)))
            .forEach(found::add);
      }
    }
    found.sort(Comparator.comparing(ResultCollector::toUnixString));
    return found;
  }

  private static List<Path> findXmlFiles(List<Path> testResultDirs) throws IOException {
    var found = new ArrayList<Path>();
    for (var testResultDir : testResultDirs) {
      try (Stream<Path> paths = Files.walk(testResultDir)) {
        paths
            .filter(Files::isRegularFile)
            .filter(path -> fileName(path).endsWith(".xml"))
            .forEach(found::add);
      } catch (UncheckedIOException e) {
        throw e.getCause();
      }
    }
    found.sort(Comparator.comparing(ResultCollector::toUnixString));
    return found;
  }

  private static String aggregatedFileName(Path sourceXml) {
    var normalized = sourceXml.normalize();
    var parts = new ArrayList<String>(AGGREGATED_NAME_FIELDS_FROM_END.length);
    var nameCount = normalized.getNameCount();
    for (var fieldFromEnd : AGGREGATED_NAME_FIELDS_FROM_END) {
      var index = nameCount - fieldFromEnd;
      if (index >= 0) {
        parts.add(normalized.getName(index).toString());
      }
    }
    return String.join("_", parts);
  }

  private static String fileName(Path path) {
    var fileName = path.getFileName();
    return fileName == null ? "" : fileName.toString();
  }

  static String toUnixString(Path path) {
    return path.toString().replace(path.getFileSystem().getSeparator(), "/");
  }
}
