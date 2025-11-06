package datadog.nativeloader;

import java.net.URL;
import java.nio.file.Path;

public interface LibraryLoadingListener {
  /**
   * Called when a dynamic library is resolved This includes resolving a pre-loaded or already
   * loaded library
   *
   * <p>If the library is pre-loaded <code>optionalUrl</code> will be <code>null</code>
   */
  default void onResolveDynamic(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      boolean isPreloaded,
      URL optionalUrl) {}

  /**
   * Called when a dynamic library fails to resolve This can occur because the library was not found
   * -- or an exception occurred during resolution
   */
  default void onResolveDynamicFailure(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      Throwable optionalCause) {}

  /**
   * Called when a dynamic library loads successfully This includes loading a pre-loaded or already
   * loaded library
   */
  default void onLoad(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      boolean isPreloaded,
      Path optionalLibPath) {}

  /** Called when a dynamic library fails to load */
  default void onLoadFailure(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      Throwable optionalCause) {}

  /** Called when a temp file is successfully created to hold the library */
  default void onTempFileCreated(
      PlatformSpec platformSpec, String optionalComponent, String libName, Path tempFile) {}

  /** Called when a temp file could not be created */
  default void onTempFileCreationFailure(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      Path tempDir,
      String libExt,
      Path optionalTempFile,
      Throwable optionalCause) {}

  /** Called when a temp is cleaned up */
  default void onTempFileCleanup(
      PlatformSpec platformSpec, String optionalComponent, String libName, Path tempFile) {}
}

/**
 * "safe" listeners are used inside NativeLoader to avoid exceptions leaking out
 *
 * <p>The "safe" listeners are {@link CompositeLibraryLoadingListener} used to wrap regular
 * listeners and {@link NopLibraryLoadingListener} used to optimize the nop case.
 */
abstract class SafeLibraryLoadingListener implements LibraryLoadingListener {
  /** Used to create a new safe listener with the provided listeners append onto this one */
  public abstract SafeLibraryLoadingListener join(LibraryLoadingListener... listeners);

  /** Indicates if all listener operates are nops */
  public abstract boolean isNop();
}
