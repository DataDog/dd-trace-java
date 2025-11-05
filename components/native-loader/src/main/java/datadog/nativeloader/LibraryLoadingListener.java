package datadog.nativeloader;

import java.net.URL;
import java.nio.file.Path;

public interface LibraryLoadingListener {
  default void onResolveDynamic(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      boolean isPreloaded,
      URL optionalUrl) {}

  default void onResolveDynamicFailure(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      LibraryLoadException optionalCause) {}

  default void onLoad(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      boolean isPreloaded,
      Path optionalLibPath) {}

  default void onLoadFailure(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      LibraryLoadException optionalCause) {}

  default void onTempFileCreated(
      PlatformSpec platformSpec, String optionalComponent, String libName, Path tempFile) {}

  default void onTempFileCreationFailure(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      Path tempDir,
      String libExt,
      Path optionalTempFile,
      Throwable optionalCause) {}

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
