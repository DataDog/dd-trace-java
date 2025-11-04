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

abstract class SafeLibraryLoadingListener implements LibraryLoadingListener {
  public abstract SafeLibraryLoadingListener join(LibraryLoadingListener listener);

  public abstract boolean isEmpty();
}
