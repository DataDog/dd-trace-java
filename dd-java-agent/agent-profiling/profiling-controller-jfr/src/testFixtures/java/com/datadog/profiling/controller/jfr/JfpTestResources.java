package com.datadog.profiling.controller.jfr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicReference;

public final class JfpTestResources {
  private static final AtomicReference<String> overrides = new AtomicReference<>();
  private static final AtomicReference<String> overridesOldObjectSample = new AtomicReference<>();
  private static final AtomicReference<String> overridesObjectAllocation = new AtomicReference<>();
  private static final AtomicReference<String> overridesNativeMethodSample =
      new AtomicReference<>();

  private JfpTestResources() {}

  public static String overrides() {
    return get(overrides, "overrides.jfp");
  }

  public static String overridesOldObjectSample() {
    return get(overridesOldObjectSample, "overrides-oldobjectsample.jfp");
  }

  public static String overridesObjectAllocation() {
    return get(overridesObjectAllocation, "overrides-objectallocation.jfp");
  }

  public static String overridesNativeMethodSample() {
    return get(overridesNativeMethodSample, "overrides-nativemethodsample.jfp");
  }

  private static String get(AtomicReference<String> ref, String resourceName) {
    String v = ref.get();
    if (v != null) {
      return v;
    }
    String computed = extract(resourceName);
    return ref.compareAndSet(null, computed) ? computed : ref.get();
  }

  // Resources packaged in the testFixtures jar are not directly accessible as filesystem paths.
  // Extract to a temp file so callers can use the path with java.io.File.
  private static String extract(String resourceName) {
    try {
      Path temp = Files.createTempFile(resourceName.replace('.', '_'), ".jfp");
      temp.toFile().deleteOnExit();
      try (InputStream is =
          JfpTestResources.class.getClassLoader().getResourceAsStream(resourceName)) {
        if (is == null) {
          throw new IllegalStateException("Test resource not found on classpath: " + resourceName);
        }
        Files.copy(is, temp, StandardCopyOption.REPLACE_EXISTING);
      }
      return temp.toAbsolutePath().toString();
    } catch (IOException e) {
      throw new RuntimeException("Failed to extract test resource: " + resourceName, e);
    }
  }
}
