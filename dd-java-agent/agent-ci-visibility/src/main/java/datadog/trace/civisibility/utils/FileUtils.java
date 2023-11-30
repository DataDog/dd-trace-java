package datadog.trace.civisibility.utils;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

@SuppressForbidden
public abstract class FileUtils {

  private FileUtils() {}

  public static void delete(Path directory) throws IOException {
    Files.walkFileTree(
        directory,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /**
   * Search the parent path that contains the target file. If the current path does not have the
   * target file, the method continues with the parent path. If the path is not found, it returns
   * null.
   *
   * @param current
   * @param target
   * @return the parent path that contains the target file.
   */
  public static Path findParentPathBackwards(
      final Path current, final String target, final boolean isTargetDirectory) {
    if (current == null || target == null || target.isEmpty()) {
      return null;
    }

    final Path targetPath = current.resolve(target);
    if (Files.exists(targetPath)) {
      if (isTargetDirectory && Files.isDirectory(targetPath)) {
        return current;
      } else if (!isTargetDirectory && Files.isRegularFile(targetPath)) {
        return current;
      } else {
        return findParentPathBackwards(current.getParent(), target, isTargetDirectory);
      }
    } else {
      return findParentPathBackwards(current.getParent(), target, isTargetDirectory);
    }
  }

  public static String expandTilde(final String path) {
    if (path == null || !path.startsWith("~")) {
      return path;
    }

    if (!path.equals("~") && !path.startsWith("~/")) {
      // Home dir expansion is not supported for other user.
      // Returning path without modifications.
      return path;
    }

    return path.replaceFirst("^~", System.getProperty("user.home"));
  }
}
