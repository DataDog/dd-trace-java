package datadog.nativeloader;

import java.io.File;
import java.nio.file.Path;

/**
 * Represents a resolved library
 *
 * <ul>
 *   <li>library may be preloaded - with no backing optionalFile
 *   <li>regular optionalFile - that doesn't require clean-up
 *   <li>temporary optionalFile - copying from another source - that does require clean-up
 * </ul>
 */
public final class LibFile implements AutoCloseable {
  static final boolean NO_CLEAN_UP = false;
  static final boolean CLEAN_UP = true;

  static final LibFile preloaded(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      SafeLibraryLoadingListener listeners) {
    return new LibFile(platformSpec, optionalComponent, libName, null, NO_CLEAN_UP, listeners);
  }

  static final LibFile fromFile(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      File optionalFile,
      SafeLibraryLoadingListener listeners) {
    return new LibFile(
        platformSpec, optionalComponent, libName, optionalFile, NO_CLEAN_UP, listeners);
  }

  static final LibFile fromTempFile(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      File optionalFile,
      SafeLibraryLoadingListener listeners) {
    return new LibFile(platformSpec, optionalComponent, libName, optionalFile, CLEAN_UP, listeners);
  }

  final PlatformSpec platformSpec;
  final String optionalComponent;
  final String libName;

  final File optionalFile;
  final boolean needsCleanup;

  final SafeLibraryLoadingListener listeners;

  LibFile(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      File optionalFile,
      boolean needsCleanup,
      SafeLibraryLoadingListener listeners) {
    this.platformSpec = platformSpec;
    this.optionalComponent = optionalComponent;
    this.libName = libName;

    this.optionalFile = optionalFile;
    this.needsCleanup = needsCleanup;

    this.listeners = listeners;
  }

  /** Indicates if this library was "preloaded" */
  public boolean isPreloaded() {
    return (this.optionalFile == null);
  }

  /** Loads the underlying library into the JVM */
  public void load() throws LibraryLoadException {
    boolean isPreloaded = this.isPreloaded();
    if (isPreloaded) {
      this.listeners.onLoad(
          this.platformSpec, this.optionalComponent, this.libName, isPreloaded, null);
      return;
    }

    try {
      Runtime.getRuntime().load(this.getAbsolutePath());

      this.listeners.onLoad(
          this.platformSpec,
          this.optionalComponent,
          this.libName,
          isPreloaded,
          this.optionalFile.toPath());
    } catch (Throwable t) {
      LibraryLoadException ex = new LibraryLoadException(this.libName, t);
      this.listeners.onLoadFailure(this.platformSpec, this.optionalComponent, this.libName, ex);
      throw ex;
    }
  }

  /** Provides a File to the library -- returns null for pre-loaded libraries */
  public final File toFile() {
    return this.optionalFile;
  }

  /** Provides a Path to the library -- return null for pre-loaded libraries */
  public final Path toPath() {
    return this.optionalFile == null ? null : this.optionalFile.toPath();
  }

  /** Provides the an absolute path to the library -- returns null for pre-loaded libraries */
  public final String getAbsolutePath() {
    return this.optionalFile == null ? null : this.optionalFile.getAbsolutePath();
  }

  /** Schedules clean-up of underlying optionalFile -- if the file is a temp file */
  @Override
  public void close() {
    if (this.needsCleanup) {
      boolean done = NativeLoader.delete(this.optionalFile);
      if (done) {
        this.listeners.onTempFileCleanup(
            this.platformSpec, this.optionalComponent, this.libName, this.optionalFile.toPath());
      }
    }
  }
}
