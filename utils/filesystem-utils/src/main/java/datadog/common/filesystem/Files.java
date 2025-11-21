package datadog.common.filesystem;

import java.io.File;
import javax.annotation.Nonnull;

/** Utility methods related to file operations. */
public class Files {

  private Files() {
    // Prevent instantiation
  }

  /**
   * Determines whether the given file exists on the filesystem.
   *
   * <p>This method wraps {@link File#exists()} and safely handles {@link SecurityException}s that
   * may occur if the caller does not have the required permissions to examine the file. In such
   * cases, this method returns {@code false}.
   *
   * @param file the file to check for existence; must not be {@code null}
   * @return {@code true} if the file exists and can be queried, {@code false} otherwise
   */
  public static boolean exists(@Nonnull File file) {
    try {
      return file.exists();
    } catch (SecurityException ignored) {
      return false;
    }
  }
}
