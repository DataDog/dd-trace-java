import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class SourceFileResolver {
  private static final String UNKNOWN = "UNKNOWN";
  // Kotlin's "enum class" declaration is indexed through its "class" keyword.
  private static final Pattern TYPE_DECLARATION =
      Pattern.compile(
          "\\b(?:class|interface|enum(?!\\s+class\\b)|record|object|trait)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b");
  private static final Pattern PACKAGE_DECLARATION =
      Pattern.compile("^\\s*package\\s+([A-Za-z_$][A-Za-z0-9_$.]*)");

  private final String workspacePrefix;
  private final Map<String, SourceIndex> indexes = new HashMap<>();

  SourceFileResolver(Path workspaceDir) {
    this.workspacePrefix = ResultCollector.toUnixString(workspaceDir) + "/";
  }

  String resolve(Path resultXml) throws IOException {
    var resultXmlPath = ResultCollector.toUnixString(resultXml);
    var sourceRoot = sourceRoot(resultXmlPath);
    if (resultXmlPath.contains("#")) {
      return sourceRoot;
    }

    var className = className(resultXml);
    if (className.isEmpty()) {
      return UNKNOWN;
    }

    return indexes.computeIfAbsent(sourceRoot, SourceIndex::new).resolve(className);
  }

  private String sourceRoot(String resultXmlPath) {
    var buildIndex = resultXmlPath.lastIndexOf("/build/");
    var projectPath = buildIndex >= 0 ? resultXmlPath.substring(0, buildIndex) : resultXmlPath;
    if (projectPath.startsWith(workspacePrefix)) {
      projectPath = projectPath.substring(workspacePrefix.length());
    }
    return projectPath + "/src";
  }

  private static String className(Path resultXml) {
    var fileName = resultXml.getFileName();
    if (fileName == null) {
      return "";
    }

    var name = fileName.toString();
    if (name.endsWith(".xml")) {
      name = name.substring(0, name.length() - ".xml".length());
    }

    var testPrefix = name.lastIndexOf("TEST-");
    if (testPrefix >= 0) {
      name = name.substring(testPrefix + "TEST-".length());
    }

    return name;
  }

  private static final class SourceIndex {
    private final String sourceRoot;
    private final Map<String, List<Path>> classLocations = new HashMap<>();
    private boolean indexed;

    private SourceIndex(String sourceRoot) {
      this.sourceRoot = sourceRoot;
    }

    private String resolve(String className) {
      try {
        indexIfNecessary();
      } catch (IOException e) {
        return UNKNOWN;
      }

      var locations = locations(className);
      if (locations.isEmpty()) {
        return UNKNOWN;
      }

      var commonRoot = commonRoot(locations);
      if (commonRoot == null) {
        return UNKNOWN;
      }
      return "/" + ResultCollector.toUnixString(commonRoot);
    }

    private List<Path> locations(String className) {
      while (true) {
        var locations = classLocations.get(className);
        if (locations != null) {
          return locations;
        }

        var innerClassSeparator = className.lastIndexOf('$');
        if (innerClassSeparator <= className.lastIndexOf('.')) {
          return List.of();
        }
        className = className.substring(0, innerClassSeparator);
      }
    }

    private void indexIfNecessary() throws IOException {
      if (indexed) {
        return;
      }
      indexed = true;

      var root = Path.of(sourceRoot);
      if (!Files.isDirectory(root)) {
        return;
      }

      try (var paths = Files.walk(root)) {
        var iterator =
            paths.filter(Files::isRegularFile).filter(SourceIndex::isSourceFile).iterator();
        while (iterator.hasNext()) {
          try {
            index(iterator.next());
          } catch (IOException ignored) {
            // Match grep's best-effort behavior from the old shell implementation.
          }
        }
      }
    }

    private static boolean isSourceFile(Path path) {
      var fileName = path.getFileName();
      if (fileName == null) {
        return false;
      }
      var name = fileName.toString();
      return name.endsWith(".java")
          || name.endsWith(".groovy")
          || name.endsWith(".kt")
          || name.endsWith(".scala");
    }

    private void index(Path sourceFile) throws IOException {
      try (BufferedReader reader = Files.newBufferedReader(sourceFile, StandardCharsets.UTF_8)) {
        var packageName = "";
        String line;
        while ((line = reader.readLine()) != null) {
          if (packageName.isEmpty()) {
            var packageMatcher = PACKAGE_DECLARATION.matcher(line);
            if (packageMatcher.find()) {
              packageName = packageMatcher.group(1) + '.';
            }
          }
          var matcher = TYPE_DECLARATION.matcher(line);
          while (matcher.find()) {
            classLocations
                .computeIfAbsent(packageName + matcher.group(1), ignored -> new ArrayList<>())
                .add(sourceFile);
          }
        }
      }
    }

    private static Path commonRoot(List<Path> locations) {
      var commonRoot = locations.get(0);
      for (var location : locations) {
        while (commonRoot != null && !location.startsWith(commonRoot)) {
          commonRoot = commonRoot.getParent();
        }
        if (commonRoot == null) {
          return null;
        }
      }
      return commonRoot.getNameCount() == 0 ? null : commonRoot;
    }
  }
}
