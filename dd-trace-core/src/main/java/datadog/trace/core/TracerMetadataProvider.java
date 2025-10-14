package datadog.trace.core;

import datadog.libconfig.NativeTracerMetadata;
import datadog.libconfig.NoOpTracerMetadata;
import datadog.libconfig.TracerMetadata;
import datadog.nativeloader.LibraryLoadException;
import datadog.nativeloader.NativeLoader;

final class TracerMetadataProvider {
  static final TracerMetadata INSTANCE = create();

  static final NativeLoader nativeLoader() {
    return NativeLoader.builder()
        .fromClassLoader(TracerMetadataProvider.class.getClassLoader(), "lib")
        .build();
  }

  static final TracerMetadata create() {
    try {
      nativeLoader().load("lib");
    } catch (LibraryLoadException e) {
      return new NoOpTracerMetadata();
    }

    try {
      return new NativeTracerMetadata();
    } catch (Throwable t) {
      return new NoOpTracerMetadata();
    }
  }
}
