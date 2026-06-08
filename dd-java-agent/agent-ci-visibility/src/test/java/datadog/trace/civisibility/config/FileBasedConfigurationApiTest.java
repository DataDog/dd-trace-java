package datadog.trace.civisibility.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBasedConfigurationApiTest extends AbstractConfigurationApiContractTest {

  @TempDir Path tmp;

  @Override
  protected ConfigurationApi apiReturning(Endpoint endpoint, String responseBody)
      throws IOException {
    Path file = writeText("payload.json", responseBody);
    switch (endpoint) {
      case SETTINGS:
        return new FileBasedConfigurationApi(file, null, null, null, null);
      case SKIPPABLE_TESTS:
        return new FileBasedConfigurationApi(null, file, null, null, null);
      case FLAKY_TESTS:
        return new FileBasedConfigurationApi(null, null, file, null, null);
      case KNOWN_TESTS:
        return new FileBasedConfigurationApi(null, null, null, file, null);
      case TEST_MANAGEMENT:
        return new FileBasedConfigurationApi(null, null, null, null, file);
      default:
        throw new IllegalArgumentException("Unsupported endpoint: " + endpoint);
    }
  }

  @Test
  void returnsDefaultsWhenAllPathsAreNull() throws IOException {
    FileBasedConfigurationApi api = new FileBasedConfigurationApi(null, null, null, null, null);

    assertSame(CiVisibilitySettings.DEFAULT, api.getSettings(ENV));
    assertSame(SkippableTests.EMPTY, api.getSkippableTests(ENV));
    assertEquals(0, api.getFlakyTestsByModule(ENV).size());
    assertEquals(0, api.getKnownTestsByModule(ENV).size());
    assertEquals(0, api.getTestManagementTestsByModule(ENV, null, null).size());
  }

  @Test
  void knownTestsReturnsNullWhenResponseHasNoTests() throws IOException {
    // Matches the backend API contract: empty-but-present known-tests payload → null
    Path file = writeText("empty-known.json", "{\"data\":{\"attributes\":{\"tests\":{}}}}");

    FileBasedConfigurationApi api = new FileBasedConfigurationApi(null, null, null, file, null);

    assertNull(api.getKnownTestsByModule(ENV));
  }

  @Test
  void propagatesIOExceptionForMissingFile() {
    Path missing = tmp.resolve("does-not-exist.json");
    FileBasedConfigurationApi api = new FileBasedConfigurationApi(missing, null, null, null, null);

    assertThrowsIO(() -> api.getSettings(ENV));
  }

  private Path writeText(String name, String content) throws IOException {
    Path p = tmp.resolve(name);
    Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    return p;
  }

  private static void assertThrowsIO(ThrowingRunnable runnable) {
    try {
      runnable.run();
    } catch (IOException expected) {
      return;
    } catch (Throwable t) {
      throw new AssertionError("Expected IOException but got " + t, t);
    }
    throw new AssertionError("Expected IOException but none thrown");
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws IOException;
  }
}
