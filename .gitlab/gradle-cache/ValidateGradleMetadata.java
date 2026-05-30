import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Validates Gradle immutable-workspace metadata files before CI decides whether to clear them.
 */
class ValidateGradleMetadata {
  private static final int EXIT_VALID = 0;
  // The shell wrapper maps this to 1 after the Java source launcher succeeds.
  // This keeps launcher failures from looking like damaged metadata to the CI cleanup block.
  private static final int EXIT_DAMAGED = 42;
  private static final int EXIT_VALIDATOR_UNAVAILABLE = 2;
  private static final Pattern TEMPORARY_WORKSPACE =
      Pattern.compile(
          ".*-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
              + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  public static void main(String[] args) {
    try {
      var workspaces = workspacesFrom(args);
      var reader = new GradleMetadataReader();
      var damaged = false;

      for (var workspace : workspaces) {
        var result = reader.validate(workspace);
        if (result != null) {
          damaged = true;
          System.out.printf("%s\t%s\t%s%n", result.size(), result.reason(), result.workspace());
        }
      }

      System.exit(damaged ? EXIT_DAMAGED : EXIT_VALID);
    } catch (ValidatorUnavailableException e) {
      System.err.println(e.getMessage());
      System.exit(EXIT_VALIDATOR_UNAVAILABLE);
    } catch (Exception e) {
      System.err.println("Gradle metadata validator failed: " + summarize(e));
      System.exit(EXIT_VALIDATOR_UNAVAILABLE);
    }
  }

  private static List<Path> workspacesFrom(String[] args) throws IOException {
    if (args.length == 2 && "--workspace-list".equals(args[0])) {
      return readNulSeparatedPaths(Path.of(args[1]));
    }
    if (args.length > 0 && args[0].startsWith("--")) {
      throw new IllegalArgumentException(
          "usage: ValidateGradleMetadata [--workspace-list file] [workspace...]");
    }

    var workspaces = new ArrayList<Path>();
    for (var arg : args) {
      workspaces.add(Path.of(arg));
    }
    return workspaces;
  }

  private static List<Path> readNulSeparatedPaths(Path listFile) throws IOException {
    var bytes = Files.readAllBytes(listFile);
    var workspaces = new ArrayList<Path>();
    var start = 0;
    for (var i = 0; i < bytes.length; i++) {
      if (bytes[i] == 0) {
        addPath(bytes, start, i, workspaces);
        start = i + 1;
      }
    }
    addPath(bytes, start, bytes.length, workspaces);
    return workspaces;
  }

  private static void addPath(byte[] bytes, int start, int end, List<Path> workspaces) {
    if (end <= start) {
      return;
    }
    var path = new String(bytes, start, end - start, StandardCharsets.UTF_8);
    if (!path.isBlank()) {
      workspaces.add(Path.of(path));
    }
  }

  private record DamagedWorkspace(Path workspace, String size, String reason) {
  }

  private static final class GradleMetadataReader {
    private final Object store;
    private final Method loadWorkspaceMetadata;

    private GradleMetadataReader() {
      try {
        var storeClass =
            Class.forName(
                "org.gradle.internal.execution.history.impl.DefaultImmutableWorkspaceMetadataStore");
        store = storeClass.getDeclaredConstructor().newInstance();
        loadWorkspaceMetadata =
            storeClass.getMethod("loadWorkspaceMetadata", java.io.File.class);
      } catch (ReflectiveOperationException | LinkageError e) {
        throw new ValidatorUnavailableException(
            "Gradle metadata validator could not load Gradle metadata reader: " + summarize(e), e);
      }
    }

    private DamagedWorkspace validate(Path workspace) {
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

      try {
        var result = loadWorkspaceMetadata.invoke(store, workspace.toFile());
        if (result instanceof Optional<?> optional && optional.isEmpty()) {
          return new DamagedWorkspace(workspace, size, "Gradle returned empty metadata");
        }
        if (result == null) {
          return new DamagedWorkspace(workspace, size, "Gradle returned null metadata");
        }
        return null;
      } catch (InvocationTargetException e) {
        var cause = e.getCause();
        if (cause instanceof LinkageError) {
          throw new ValidatorUnavailableException(
              "Gradle metadata validator could not execute Gradle metadata reader: "
                  + summarize(cause),
              cause);
        }
        return new DamagedWorkspace(workspace, size, summarize(cause));
      } catch (ReflectiveOperationException | RuntimeException e) {
        throw new ValidatorUnavailableException(
            "Gradle metadata validator could not execute Gradle metadata reader: "
                + summarize(e),
            e);
      }
    }
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

  private static final class ValidatorUnavailableException extends RuntimeException {
    private ValidatorUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
