import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates Gradle immutable-workspace metadata files before CI decides whether to clear them.
 *
 * <p>Given a Gradle version, enumerates the immutable-workspace dirs under the project-local cache
 * and, for each, asks Gradle's own reader to deserialize its {@code metadata.bin}. Damaged
 * (truncated or missing) entries are printed to stdout as {@code <size>\t<reason>\t<workspace>} and
 * the process exits {@code 65} (EX_DATAERR). Any other failure means the validator could not run,
 * so it exits {@code 2} and CI leaves the cache untouched rather than mistaking an unavailable
 * validator for cache corruption.
 */
class ValidateGradleMetadata {
  private static final int EXIT_VALID = 0;
  private static final int EXIT_DAMAGED = 65;
  private static final int EXIT_UNAVAILABLE = 2;

  // CI always runs from the repository root, where the project-local Gradle cache lives.
  private static final Path CACHES_DIR = Path.of(".gradle/caches");

  // Folders with the directory depth to check.
  private static final Map<String, Integer> WORKSPACE_CATEGORIES =
      Map.of(
          "dependencies-accessors", 1,
          "groovy-dsl", 1,
          "kotlin-dsl", 2,
          "transforms", 1);

  private static final Pattern TEMPORARY_WORKSPACE =
      Pattern.compile(".*-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  // Gradle's own metadata reader, resolved reflectively.
  private static Object metadataStore;
  private static Method metadataLoadMethod;

  public static void main(String[] args) {
    try {
      loadMetadataReader();

      var damaged = false;
      for (var workspace : enumerateWorkspaces(args[0])) {
        var result = validate(workspace);
        if (result != null) {
          damaged = true;
          System.out.printf("%s\t%s\t%s%n", result.size(), result.reason(), result.workspace());
        }
      }
      System.exit(damaged ? EXIT_DAMAGED : EXIT_VALID);
    } catch (Exception e) {
      System.err.println("Gradle metadata validator unavailable: " + summarize(e));
      System.exit(EXIT_UNAVAILABLE);
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
    for (var child : childDirectories(dir)) {
      collectAtDepth(child, depth - 1, out);
    }
  }

  private static List<Path> childDirectories(Path dir) throws IOException {
    try (var entries = Files.list(dir)) {
      return entries.filter(Files::isDirectory).collect(Collectors.toList());
    }
  }

  private static void loadMetadataReader() {
    try {
      var storeClass =
          Class.forName("org.gradle.internal.execution.history.impl.DefaultImmutableWorkspaceMetadataStore");
      metadataStore = storeClass.getDeclaredConstructor().newInstance();
      metadataLoadMethod = storeClass.getMethod("loadWorkspaceMetadata", java.io.File.class);
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new IllegalStateException("could not load Gradle metadata reader: " + summarize(e));
    }
  }

  private static DamagedWorkspace validate(Path workspace) {
    // Gradle temporary workspaces are named <immutable-workspace>-<uuid> and intentionally may not
    // have metadata.bin until Gradle moves them into their immutable location.
    if (TEMPORARY_WORKSPACE.matcher(workspace.getFileName().toString()).matches()) {
      return null;
    }

    var metadata = workspace.resolve("metadata.bin");
    if (!Files.isRegularFile(metadata)) {
      return new DamagedWorkspace(workspace, "missing", "metadata.bin is missing");
    }

    String size;
    try {
      size = Long.toString(Files.size(metadata));
    } catch (IOException e) {
      return new DamagedWorkspace(workspace, "unknown", "metadata.bin size check failed");
    }

    // A successful return means Gradle's own reader fully deserialized metadata.bin;
    // a truncated file throws mid-read and surfaces here as an InvocationTargetException.
    try {
      metadataLoadMethod.invoke(metadataStore, workspace.toFile());
      return null;
    } catch (InvocationTargetException e) {
      var cause = e.getCause();
      if (cause instanceof LinkageError) {
        throw new IllegalStateException(
            "could not execute Gradle metadata reader: " + summarize(cause));
      }
      return new DamagedWorkspace(workspace, size, summarize(cause));
    } catch (ReflectiveOperationException | RuntimeException e) {
      throw new IllegalStateException("could not execute Gradle metadata reader: " + summarize(e));
    }
  }

  private record DamagedWorkspace(Path workspace, String size, String reason) {
  }

  private static String summarize(Throwable throwable) {
    if (throwable == null) {
      return "unknown failure";
    }

    var root = throwable;
    while ((root instanceof InvocationTargetException
        || root instanceof UncheckedIOException
        || root.getClass().getName().equals("org.gradle.internal.UncheckedException"))
        && root.getCause() != null) {
      root = root.getCause();
    }

    var message = root.getMessage();
    if (message == null || message.isBlank()) {
      return root.getClass().getSimpleName();
    }

    return root.getClass().getSimpleName() + ": " + message.replace('\t', ' ');
  }
}
