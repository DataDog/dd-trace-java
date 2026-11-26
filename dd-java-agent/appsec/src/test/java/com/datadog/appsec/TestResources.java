package com.datadog.appsec;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Reads test resources of the appsec module. */
public final class TestResources {

  private TestResources() {}

  /** Returns the UTF-8 content of the classpath resource at {@code location}. */
  public static String readResource(String location) throws IOException {
    try (InputStream stream = TestResources.class.getClassLoader().getResourceAsStream(location)) {
      assertNotNull(stream, "missing resource " + location);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = stream.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return new String(out.toByteArray(), UTF_8);
    }
  }
}
