package datadog.communication.http.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.communication.http.okhttp.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpClientFactoryTest {

  private static final String CONFIG_PROPERTY = "dd.http.client.implementation";

  @AfterEach
  void cleanup() {
    // Clean up system property after each test
    System.clearProperty(CONFIG_PROPERTY);
  }

  @Test
  void testDefaultConfiguration() {
    // Default should be "auto" which selects based on Java version
    HttpClient.Builder builder = HttpClient.newBuilder();
    assertNotNull(builder);

    HttpClient client = builder.build();
    assertNotNull(client);
  }

  @Test
  void testAutoConfiguration() {
    System.setProperty(CONFIG_PROPERTY, "auto");

    HttpClient.Builder builder = HttpClient.newBuilder();
    assertNotNull(builder);

    HttpClient client = builder.build();
    assertNotNull(client);

    // On Java 11+, should use JDK client (but we only have OkHttp for now)
    // For now, verify it builds successfully
    assertTrue(client instanceof OkHttpClient);
  }

  @Test
  void testOkHttpConfiguration() {
    System.setProperty(CONFIG_PROPERTY, "okhttp");

    HttpClient.Builder builder = HttpClient.newBuilder();
    assertNotNull(builder);

    HttpClient client = builder.build();
    assertNotNull(client);
    assertTrue(client instanceof OkHttpClient);
  }

  @Test
  void testJdkConfigurationNotYetImplemented() {
    System.setProperty(CONFIG_PROPERTY, "jdk");

    // JDK implementation not yet available in Phase 2
    // Should fall back to OkHttp with a warning or throw
    HttpClient.Builder builder = HttpClient.newBuilder();
    assertNotNull(builder);

    // For now, this will use OkHttp as fallback
    HttpClient client = builder.build();
    assertNotNull(client);
  }

  @Test
  void testInvalidConfiguration() {
    System.setProperty(CONFIG_PROPERTY, "invalid");

    // Invalid configuration should default to auto
    HttpClient.Builder builder = HttpClient.newBuilder();
    assertNotNull(builder);

    HttpClient client = builder.build();
    assertNotNull(client);
  }

  @Test
  void testCaseInsensitiveConfiguration() {
    System.setProperty(CONFIG_PROPERTY, "OKHTTP");

    HttpClient.Builder builder = HttpClient.newBuilder();
    assertNotNull(builder);

    HttpClient client = builder.build();
    assertNotNull(client);
    assertTrue(client instanceof OkHttpClient);
  }

  @Test
  void testConfigurationPreservation() {
    System.setProperty(CONFIG_PROPERTY, "okhttp");

    // Create multiple builders - should all use same implementation
    HttpClient client1 = HttpClient.newBuilder().build();
    HttpClient client2 = HttpClient.newBuilder().build();

    assertTrue(client1 instanceof OkHttpClient);
    assertTrue(client2 instanceof OkHttpClient);
  }

  @Test
  void testJavaVersionDetection() {
    // Verify we can detect Java version
    int majorVersion = getMajorJavaVersion();
    assertTrue(majorVersion >= 8);
  }

  @Test
  void testAutoSelectsBasedOnJavaVersion() {
    System.setProperty(CONFIG_PROPERTY, "auto");

    HttpClient.Builder builder = HttpClient.newBuilder();
    HttpClient client = builder.build();

    // For now, always uses OkHttp since JDK client not implemented yet
    assertTrue(client instanceof OkHttpClient);
  }

  private int getMajorJavaVersion() {
    String version = System.getProperty("java.version");
    if (version.startsWith("1.")) {
      version = version.substring(2, 3);
    } else {
      int dot = version.indexOf(".");
      if (dot != -1) {
        version = version.substring(0, dot);
      }
    }
    return Integer.parseInt(version);
  }
}
