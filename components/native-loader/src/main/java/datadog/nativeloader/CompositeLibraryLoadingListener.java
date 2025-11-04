package datadog.nativeloader;

import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;

final class CompositeLibraryLoadingListener extends SafeLibraryLoadingListener {
  private final Collection<? extends LibraryLoadingListener> listeners;

  CompositeLibraryLoadingListener(Collection<? extends LibraryLoadingListener> listeners) {
    this.listeners = listeners;
  }

  @Override
  public boolean isEmpty() {
    return this.listeners.isEmpty();
  }

  @Override
  public void onResolveDynamic(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      boolean isPreloaded,
      URL optionalUrl) {
    for (LibraryLoadingListener listener : this.listeners) {
      try {
        listener.onResolveDynamic(
            platformSpec, optionalComponent, libName, isPreloaded, optionalUrl);
      } catch (Throwable ignored) {
      }
    }
  }

  @Override
  public void onResolveDynamicFailure(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      LibraryLoadException optionalCause) {
    for (LibraryLoadingListener listener : this.listeners) {
      try {
        listener.onResolveDynamicFailure(platformSpec, optionalComponent, libName, optionalCause);
      } catch (Throwable ignored) {
      }
    }
  }

  @Override
  public void onLoad(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      boolean isPreloaded,
      Path optionalLibPath) {
    for (LibraryLoadingListener listener : this.listeners) {
      try {
        listener.onLoad(platformSpec, optionalComponent, libName, isPreloaded, optionalLibPath);
      } catch (Throwable ignored) {
      }
    }
  }

  @Override
  public void onTempFileCreated(
      PlatformSpec platformSpec, String optionalComponent, String libName, Path tempFile) {
    for (LibraryLoadingListener listener : this.listeners) {
      try {
        listener.onTempFileCreated(platformSpec, optionalComponent, libName, tempFile);
      } catch (Throwable ignored) {
      }
    }
  }

  @Override
  public void onTempFileCreationFailure(
      PlatformSpec platformSpec,
      String optionalComponent,
      String libName,
      Path tempDir,
      String libExt,
      Path optionalTempFile,
      Throwable optionalCause) {
    for (LibraryLoadingListener listener : this.listeners) {
      try {
        listener.onTempFileCreationFailure(
            platformSpec,
            optionalComponent,
            libName,
            tempDir,
            libExt,
            optionalTempFile,
            optionalCause);
      } catch (Throwable ignored) {
      }
    }
  }

  @Override
  public void onTempFileCleanup(
      PlatformSpec platformSpec, String optionalComponent, String libName, Path tempPath) {
    for (LibraryLoadingListener listener : this.listeners) {
      try {
        listener.onTempFileCleanup(platformSpec, optionalComponent, libName, tempPath);
      } catch (Throwable ignored) {
      }
    }
  }

  @Override
  public SafeLibraryLoadingListener join(LibraryLoadingListener listener) {
    ArrayList<LibraryLoadingListener> listeners = new ArrayList<>(this.listeners.size() + 1);
    listeners.addAll(this.listeners);
    listeners.add(listener);
    return new CompositeLibraryLoadingListener(listeners);
  }
}
