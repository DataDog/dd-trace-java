import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class SourceFileResolver {
  private static final String UNKNOWN = "UNKNOWN";
  private static final String IDENTIFIER =
      "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*";
  // Possessive repeats keep long string literals from exhausting the regex stack.
  private static final Pattern COMMENTS_AND_STRINGS =
      Pattern.compile(
          "//[^\\r\\n]*|/\\*[\\s\\S]*?\\*/"
              + "|\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''"
              + "|\"(?:\\\\.|[^\"\\\\])*+\"|'(?:\\\\.|[^'\\\\])*+'");
  // Kotlin's "enum class" declaration is indexed through its "class" keyword.
  private static final Pattern TYPE_OR_SCOPE =
      Pattern.compile(
          "(?<![.:])\\b(?:class|interface|enum(?!\\s+class\\b)|record|object|trait)\\s+("
              + IDENTIFIER
              + ")|[{}()]");
  private static final Pattern PACKAGE_DECLARATION =
      Pattern.compile("\\bpackage\\s+(" + IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")*)");

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
    // Module names such as build-helper are not build output directories.
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

      // Duplicate binary names across source sets still fall back to their common directory.
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

        // Nested reports use binary names; never trim a '$' from the package.
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
      // shortcut: parse common JVM syntax; use compiler metadata if other syntax causes ambiguity.
      // Comments and literals can contain declarations or braces that are not source structure.
      var source =
          COMMENTS_AND_STRINGS
              .matcher(Files.readString(sourceFile, StandardCharsets.UTF_8))
              .replaceAll(" ");
      // Packages may span lines and contain Unicode identifiers or comments between segments.
      var packageMatcher = PACKAGE_DECLARATION.matcher(source);
      var packageName =
          packageMatcher.find() ? packageMatcher.group(1).replaceAll("\\s+", "") + '.' : "";
      var enclosingTypes = new ArrayDeque<TypeScope>();
      var braceDepth = 0;
      var parentheses = 0;
      var declarationParentheses = 0;
      String pendingType = null;
      var matcher = TYPE_OR_SCOPE.matcher(source);
      while (matcher.find()) {
        var typeName = matcher.group(1);
        if (typeName != null) {
          // Helper.OuterTest must not be indexed as the top-level OuterTest.
          pendingType =
              (enclosingTypes.isEmpty() ? packageName : enclosingTypes.peek().name() + '$')
                  + typeName;
          classLocations.computeIfAbsent(pendingType, ignored -> new ArrayList<>()).add(sourceFile);
          declarationParentheses = parentheses;
          continue;
        }
        switch (matcher.group()) {
          case "(" -> parentheses++;
          case ")" -> parentheses--;
          case "{" -> {
            braceDepth++;
            // Annotation arrays in a type header precede the actual type body.
            if (pendingType != null && parentheses == declarationParentheses) {
              enclosingTypes.push(new TypeScope(pendingType, braceDepth));
              pendingType = null;
            }
          }
          case "}" -> {
            if (!enclosingTypes.isEmpty() && enclosingTypes.peek().depth() == braceDepth) {
              enclosingTypes.pop();
            }
            braceDepth--;
          }
        }
      }
    }

    private record TypeScope(String name, int depth) {}

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
