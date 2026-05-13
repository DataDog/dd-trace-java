package com.datadog.profiling.controller.jfr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class JfpTestResources {
  public static final String OVERRIDES = extract("overrides.jfp");
  public static final String OVERRIDES_OLD_OBJECT_SAMPLE = extract("overrides-oldobjectsample.jfp");
  public static final String OVERRIDES_OBJECT_ALLOCATION =
      extract("overrides-objectallocation.jfp");
  public static final String OVERRIDES_NATIVE_METHOD_SAMPLE =
      extract("overrides-nativemethodsample.jfp");

  private JfpTestResources() {}

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
