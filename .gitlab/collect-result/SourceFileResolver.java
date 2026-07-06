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
  private static final Pattern CLASS_DECLARATION =
      Pattern.compile("\\b(?:static\\s+)?class\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b");

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
    var buildIndex = resultXmlPath.indexOf("/build");
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

    var packageSeparator = name.lastIndexOf('.');
    if (packageSeparator >= 0) {
      name = name.substring(packageSeparator + 1);
    }

    var innerClassSeparator = name.lastIndexOf('$');
    if (innerClassSeparator >= 0) {
      name = name.substring(innerClassSeparator + 1);
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
      var locations = new ArrayList<Path>();
      for (var entry : classLocations.entrySet()) {
        if (entry.getKey().startsWith(className)) {
          locations.addAll(entry.getValue());
        }
      }
      return locations;
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
        String line;
        while ((line = reader.readLine()) != null) {
          var matcher = CLASS_DECLARATION.matcher(line);
          while (matcher.find()) {
            classLocations
                .computeIfAbsent(matcher.group(1), ignored -> new ArrayList<>())
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
