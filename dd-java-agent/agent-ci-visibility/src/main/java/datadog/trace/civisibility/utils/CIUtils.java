package datadog.trace.civisibility.utils;

import java.nio.file.Files;
import java.nio.file.Path;

public final class CIUtils {

  private CIUtils() {}

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
}
