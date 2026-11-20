import static java.util.stream.Collectors.groupingBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SourceFileResolver {
  private static final String UNKNOWN = "UNKNOWN";
  private static final List<String> SOURCE_EXTENSIONS =
      List.of(".java", ".groovy", ".kt", ".scala");

  private final String workspacePrefix;
  private final Map<String, SourceIndex> indexes = new HashMap<>();

  SourceFileResolver(Path workspaceDir) {
    this.workspacePrefix = ResultCollector.toUnixString(workspaceDir) + "/";
  }

  String resolve(Path resultXml, String className) {
    var resultXmlPath = ResultCollector.toUnixString(resultXml);
    // Module names such as build-helper are not build output directories.
    var buildIndex = resultXmlPath.lastIndexOf("/build/");
    if (buildIndex < 0) {
      return UNKNOWN;
    }
    var module = resultXmlPath.substring(0, buildIndex);
    var project =
        module.startsWith(workspacePrefix) ? module.substring(workspacePrefix.length()) : module;
    var sourceRoot = Path.of(project, "src");
    if (resultXmlPath.contains("#")) {
      return ResultCollector.toUnixString(sourceRoot);
    }
    if (className.isEmpty()) {
      return UNKNOWN;
    }
    return indexes
        .computeIfAbsent(
            module, ignored -> new SourceIndex(sourceRoot, Path.of(module, "build", "classes")))
        .resolve(className);
  }

  private static final class SourceIndex {
    private final Path sourceRoot;
    private final Path classesDir;
    private final Map<String, Set<String>> sourceNames = new HashMap<>();
    private final Map<String, String> resolved = new HashMap<>();
    private Map<String, List<Path>> sourcesByFilename = Map.of();
    private boolean indexed;

    private SourceIndex(Path sourceRoot, Path classesDir) {
      this.sourceRoot = sourceRoot;
      this.classesDir = classesDir;
    }

    private String resolve(String className) {
      try {
        indexIfNecessary();
      } catch (IOException | UncheckedIOException e) {
        return UNKNOWN;
      }
      return resolved.computeIfAbsent(className, this::resolveClass);
    }

    private String resolveClass(String className) {
      var names = sourceNames.get(className);
      var locations = new ArrayList<Path>();
      if (names != null) {
        for (var name : names) {
          var candidates = sourcesByFilename.getOrDefault(name, List.of());
          var conventional =
              candidates.stream().filter(path -> matchesPackage(path, className, name)).toList();
          // Groovy and Kotlin source directories need not mirror the declared package.
          locations.addAll(conventional.isEmpty() ? candidates : conventional);
        }
      } else {
        // shortcut: missing metadata needs standard layouts; export source maps for custom layouts.
        var lookupName = className;
        while (locations.isEmpty()) {
          var simpleName = lookupName.substring(lookupName.lastIndexOf('.') + 1);
          for (var extension : SOURCE_EXTENSIONS) {
            var name = simpleName + extension;
            for (var source : sourcesByFilename.getOrDefault(name, List.of())) {
              if (matchesPackage(source, lookupName, name)) {
                locations.add(source);
              }
            }
          }
          // Try a literal '$' in a filename before falling back to the enclosing class.
          var separator = lookupName.lastIndexOf('$');
          if (separator <= lookupName.lastIndexOf('.')) {
            break;
          }
          lookupName = lookupName.substring(0, separator);
        }
      }
      if (locations.isEmpty()) {
        return UNKNOWN;
      }
      // Duplicate binary names across source sets still fall back to their common directory.
      var commonRoot = locations.getFirst();
      for (var location : locations) {
        while (commonRoot != null && !location.startsWith(commonRoot)) {
          commonRoot = commonRoot.getParent();
        }
      }
      return commonRoot == null ? UNKNOWN : "/" + ResultCollector.toUnixString(commonRoot);
    }

    private boolean matchesPackage(Path source, String className, String fileName) {
      var relative = sourceRoot.relativize(source);
      var packagePath = className.substring(0, className.lastIndexOf('.') + 1).replace('.', '/');
      // Strip src/<source set>/<language>, so other/example cannot match package example.
      return relative.getNameCount() > 2
          && ResultCollector.toUnixString(relative.subpath(2, relative.getNameCount()))
              .equals(packagePath + fileName);
    }

    private void indexIfNecessary() throws IOException {
      if (indexed) {
        return;
      }
      indexed = true;
      if (Files.isDirectory(sourceRoot)) {
        try (var paths = Files.walk(sourceRoot)) {
          sourcesByFilename =
              paths
                  .filter(Files::isRegularFile)
                  .collect(groupingBy(path -> path.getFileName().toString()));
        }
      }
      if (Files.isDirectory(classesDir)) {
        try (var paths = Files.walk(classesDir)) {
          var iterator =
              paths
                  .filter(Files::isRegularFile)
                  .filter(path -> path.toString().endsWith(".class"))
                  .iterator();
          while (iterator.hasNext()) {
            try {
              // SourceFile covers nested types and arbitrary filenames across JVM languages.
              var model = ClassFile.of().parse(iterator.next());
              var source = model.findAttribute(Attributes.sourceFile());
              if (source.isPresent()) {
                sourceNames
                    .computeIfAbsent(
                        model.thisClass().asInternalName().replace('/', '.'),
                        ignored -> new HashSet<>())
                    .add(source.get().sourceFile().stringValue());
              }
            } catch (IOException | IllegalArgumentException ignored) {
              // Keep collecting reports when compilation leaves missing or invalid class files.
            }
          }
        }
      }
    }
  }
}
