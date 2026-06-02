import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Band-aid for "FATAL: unexpected EOF" during GitLab cache extraction.
 * <p>
 * This removes only the damaged workspaces so Gradle regenerates them.
 */
class CorruptedGradleCacheMitigator {
  private static final Path CACHES_DIR = Path.of(".gradle/caches");

  // Immutable-workspace categories and the directory depth at which their workspaces live.
  private static final Map<String, Integer> WORKSPACE_CATEGORIES =
      Map.of("dependencies-accessors", 1, "groovy-dsl", 1, "kotlin-dsl", 2, "transforms", 1);

  // Gradle temporary workspaces are <workspace>-<uuid> and may legitimately lack metadata.bin.
  private static final Pattern TEMPORARY_WORKSPACE =
      Pattern.compile(
          ".*-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  // Gradle's own metadata reader, resolved reflectively.
  private static Object metadataStore;
  private static Method metadataLoadMethod;

  public static void main(String[] args) throws IOException {
    var gradleVersion = args[0];

    var damaged = new ArrayList<Path>();
    try {
      loadMetadataReader();
    } catch (Throwable e) {
      System.out.println("Gradle metadata reader unavailable; leaving cache unchanged");
      e.printStackTrace();
      return;
    }

    try {
      for (var workspace : enumerateWorkspaces(gradleVersion)) {
        if (isDamaged(workspace)) {
          damaged.add(workspace);
        }
      }
    } catch (Throwable e) {
      System.out.println("Failed to collect damaged workspaces");
      e.printStackTrace();
      return;
    }

    if (!damaged.isEmpty()) {
      System.out.println("Damaged Gradle metadata found, removing:");

      for (var workspace : damaged) {
        System.out.println("  - " + workspace);
        try {
          remove(workspace);
        } catch (Throwable e) {
          e.printStackTrace();
        }
      }
    }
  }

  private static List<Path> enumerateWorkspaces(String gradleVersion) throws IOException {
    var versionDir = CACHES_DIR.resolve(gradleVersion);
    var workspaces = new ArrayList<Path>();
    for (var category : WORKSPACE_CATEGORIES.entrySet()) {
      collectAtDepth(versionDir.resolve(category.getKey()), category.getValue(), workspaces);
    }
    return workspaces;
  }

  private static void collectAtDepth(Path dir, int depth, List<Path> out) throws IOException {
    if (!Files.isDirectory(dir)) {
      return;
    }

    if (depth == 0) {
      out.add(dir);
      return;
    }

    try (var entries = Files.list(dir)) {
      for (var child : entries.filter(Files::isDirectory).collect(Collectors.toList())) {
        collectAtDepth(child, depth - 1, out);
      }
    }
  }

  private static void loadMetadataReader() {
    try {
      var storeClass = Class.forName(
          "org.gradle.internal.execution.history.impl.DefaultImmutableWorkspaceMetadataStore");
      metadataStore = storeClass.getDeclaredConstructor().newInstance();
      metadataLoadMethod = storeClass.getMethod("loadWorkspaceMetadata", File.class);
    } catch (Throwable e) {
      throw new IllegalStateException("Failed to load Gradle metadata reader", e);
    }
  }

  private static boolean isDamaged(Path workspace) {
    if (TEMPORARY_WORKSPACE.matcher(workspace.getFileName().toString()).matches()) {
      return false;
    }

    if (!Files.isRegularFile(workspace.resolve("metadata.bin"))) {
      return true;
    }

    // A successful return means Gradle's own reader fully deserialized `metadata.bin`.
    try {
      metadataLoadMethod.invoke(metadataStore, workspace.toFile());
      return false;
    } catch (Throwable e) {
      return true; // truncated/unreadable -> remove it
    }
  }

  private static void remove(Path workspace) {
    try (var paths = Files.walk(workspace)) {
      for (var path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
        Files.deleteIfExists(path);
      }
    } catch (Throwable e) {
      throw new IllegalStateException("Failed to remove: " + workspace, e);
    }
  }
}
