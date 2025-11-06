package datadog.nativeloader;

import java.net.URL;
import java.nio.file.Path;

public class ThrowingLibraryLoadingListener implements LibraryLoadingListener {
  @Override
  public final void onLoad(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      boolean isPreloaded,
      Path optionalLibPath) {
    this.throwException("load");
  }

  @Override
  public final void onLoadFailure(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      Throwable optionalCause) {
    this.throwException("loadFailure");
  }

  @Override
  public final void onResolveDynamic(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      boolean isPreloaded,
      URL optionalUrl) {
    this.throwException("resolveDynamic");
  }

  @Override
  public final void onResolveDynamicFailure(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      Throwable optionalCause) {
    this.throwException("resolveDynamicFailure");
  }

  @Override
  public final void onTempFileCreated(
      PlatformSpec platformSpec, String optionalComponent, String libName, Path tempFile) {
    this.throwException("tempFileCreated");
  }

  @Override
  public final void onTempFileCreationFailure(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      Path tempDir,
      String libExt,
      Path optionalTempFile,
      Throwable optionalCause) {
    this.throwException("tempFileCreationFailure");
  }

  @Override
  public final void onTempFileCleanup(
      PlatformSpec platformSpec, String optionalComponent, String libName, Path tempFile) {
    this.throwException("tempFileCleanup");
  }

  void throwException(String event) {
    throw new RuntimeException(event);
  }
}
