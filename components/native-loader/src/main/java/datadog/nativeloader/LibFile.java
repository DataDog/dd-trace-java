package datadog.nativeloader;

import java.io.File;
import java.nio.file.Path;

/**
 * Represents a resolved library
 *
 * <ul>
 *   <li>library may be preloaded - with no backing file
 *   <li>regular file - that doesn't require clean-up
 *   <li>temporary file - copying from another source - that does require clean-up
 * </ul>
 */
public final class LibFile implements AutoCloseable {
  static final boolean NO_CLEAN_UP = false;
  static final boolean CLEAN_UP = true;

  static final LibFile preloaded(String libName) {
    return new LibFile(libName, null, NO_CLEAN_UP);
  }

  static final LibFile fromFile(String libName, File file) {
    return new LibFile(libName, file, NO_CLEAN_UP);
  }

  static final LibFile fromTempFile(String libName, File file) {
    return new LibFile(libName, file, CLEAN_UP);
  }

  final String libName;

  final File file;
  final boolean needsCleanup;

  LibFile(String libName, File file, boolean needsCleanup) {
    this.libName = libName;

    this.file = file;
    this.needsCleanup = needsCleanup;
  }

  /** Indicates if this library was "preloaded" */
  public boolean isPreloaded() {
    return (this.file == null);
  }

  /** Loads the underlying library into the JVM */
  public void load() throws LibraryLoadException {
    if (this.isPreloaded()) return;

    try {
      Runtime.getRuntime().load(this.getAbsolutePath());
    } catch (Throwable t) {
      throw new LibraryLoadException(this.libName, t);
    }
  }

  /** Provides a File to the library -- returns null for pre-loaded libraries */
  public final File toFile() {
    return this.file;
  }

  /** Provides a Path to the library -- return null for pre-loaded libraries */
  public final Path toPath() {
    return this.file == null ? null : this.file.toPath();
  }

  /** Provides the an absolute path to the library -- returns null for pre-loaded libraries */
  public final String getAbsolutePath() {
    return this.file == null ? null : this.file.getAbsolutePath();
  }

  /** Schedules clean-up of underlying file -- if the file is a temp file */
  @Override
  public void close() {
    if (this.needsCleanup) {
      NativeLoader.delete(this.file);
    }
  }
}
