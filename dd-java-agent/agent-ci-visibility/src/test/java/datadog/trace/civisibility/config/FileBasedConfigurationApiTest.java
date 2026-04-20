package datadog.trace.civisibility.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.civisibility.config.TestFQN;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestMetadata;
import freemarker.template.Configuration;
import freemarker.template.Template;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link FileBasedConfigurationApi} against the same response fixtures used by the HTTP
 * variant ({@code ConfigurationApiImplTest}). Sharing the {@code *-response.ftl} templates keeps
 * both code paths aligned — any change to the backend payload shape only needs to be reflected in
 * one place.
 */
class FileBasedConfigurationApiTest {

  private static final String FIXTURE_DIR = "/datadog/trace/civisibility/config/";

  private static final TracerEnvironment ENV =
      TracerEnvironment.builder()
          .service("foo")
          .env("foo_env")
          .repositoryUrl("https://github.com/DataDog/foo")
          .branch("prod")
          .sha("d64185e45d1722ab3a53c45be47accae")
          .commitMessage("full commit message")
          .build();

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
  void parsesSettings(@TempDir Path tmp) throws IOException {
    CiVisibilitySettings expected =
        new CiVisibilitySettings(
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            EarlyFlakeDetectionSettings.DEFAULT,
            TestManagementSettings.DEFAULT,
            "main",
            false);
    Map<String, Object> data = new HashMap<>();
    data.put("settings", expected);
    Path file = renderToFile(tmp, "settings-response.ftl", "settings.json", data);

    FileBasedConfigurationApi api = new FileBasedConfigurationApi(file, null, null, null, null);

    assertEquals(expected, api.getSettings(ENV));
  }

  @Test
  void parsesSettingsWithEarlyFlakeDetectionAndTestManagement(@TempDir Path tmp)
      throws IOException {
    CiVisibilitySettings expected =
        new CiVisibilitySettings(
            false,
            true,
            false,
            true,
            false,
            true,
            false,
            false,
            true,
            new EarlyFlakeDetectionSettings(
                true, Arrays.asList(new ExecutionsByDuration(1000, 3)), 10),
            new TestManagementSettings(true, 10),
            "master",
            false);
    Map<String, Object> data = new HashMap<>();
    data.put("settings", expected);
    Path file = renderToFile(tmp, "settings-response.ftl", "settings.json", data);

    FileBasedConfigurationApi api = new FileBasedConfigurationApi(file, null, null, null, null);

    assertEquals(expected, api.getSettings(ENV));
  }

  @Test
  void parsesSkippableTests(@TempDir Path tmp) throws IOException {
    Path file =
        renderToFile(tmp, "skippable-response.ftl", "skippable.json", Collections.emptyMap());

    FileBasedConfigurationApi api = new FileBasedConfigurationApi(null, file, null, null, null);
    SkippableTests result = api.getSkippableTests(ENV);

    Map<String, Map<TestIdentifier, TestMetadata>> expected = new HashMap<>();
    Map<TestIdentifier, TestMetadata> bundleA = new HashMap<>();
    bundleA.put(new TestIdentifier("suite-a", "name-a", "parameters-a"), new TestMetadata(true));
    expected.put("testBundle-a", bundleA);
    Map<TestIdentifier, TestMetadata> bundleB = new HashMap<>();
    bundleB.put(new TestIdentifier("suite-b", "name-b", null), new TestMetadata(false));
    expected.put("testBundle-b", bundleB);

    assertEquals(expected, result.getIdentifiersByModule());
    assertEquals("11223344", result.getCorrelationId());
    // coverage bitmaps encoded in the template
    assertEquals(3, result.getCoveredLinesByRelativeSourcePath().size());
    assertTrue(
        result.getCoveredLinesByRelativeSourcePath().containsKey("src/main/java/Calculator.java"));
    assertTrue(
        result.getCoveredLinesByRelativeSourcePath().containsKey("src/main/java/utils/Math.java"));
    assertTrue(
        result
            .getCoveredLinesByRelativeSourcePath()
            .containsKey("src/test/java/CalculatorTest.java"));
  }

  @Test
  void parsesFlakyTests(@TempDir Path tmp) throws IOException {
    Path file = renderToFile(tmp, "flaky-response.ftl", "flaky.json", Collections.emptyMap());

    FileBasedConfigurationApi api = new FileBasedConfigurationApi(null, null, file, null, null);
    Map<String, Collection<TestFQN>> result = api.getFlakyTestsByModule(ENV);

    Map<String, Collection<TestFQN>> expected = new HashMap<>();
    expected.put("testBundle-a", new HashSet<>(Arrays.asList(new TestFQN("suite-a", "name-a"))));
    expected.put("testBundle-b", new HashSet<>(Arrays.asList(new TestFQN("suite-b", "name-b"))));
    assertEquals(expected, result);
  }

  @Test
  void parsesKnownTests(@TempDir Path tmp) throws IOException {
    Path file = renderToFile(tmp, "known-tests-response.ftl", "known.json", Collections.emptyMap());

    FileBasedConfigurationApi api = new FileBasedConfigurationApi(null, null, null, file, null);
    Map<String, Collection<TestFQN>> result = api.getKnownTestsByModule(ENV);

    assertNotNull(result);
    assertEquals(2, result.size());
    Collection<TestFQN> bundleA = result.get("test-bundle-a");
    assertTrue(bundleA.contains(new TestFQN("test-suite-a", "test-name-1")));
    assertTrue(bundleA.contains(new TestFQN("test-suite-a", "test-name-2")));
    assertTrue(bundleA.contains(new TestFQN("test-suite-b", "another-test-name-1")));
    assertTrue(bundleA.contains(new TestFQN("test-suite-b", "test-name-2")));
    Collection<TestFQN> bundleN = result.get("test-bundle-N");
    assertTrue(bundleN.contains(new TestFQN("test-suite-M", "test-name-1")));
    assertTrue(bundleN.contains(new TestFQN("test-suite-M", "test-name-2")));
  }

  @Test
  void knownTestsReturnsNullWhenResponseHasNoTests(@TempDir Path tmp) throws IOException {
    // Matches the backend API contract: empty-but-present known-tests payload → null
    Path file = writeText(tmp, "empty-known.json", "{\"data\":{\"attributes\":{\"tests\":{}}}}");

    FileBasedConfigurationApi api = new FileBasedConfigurationApi(null, null, null, file, null);

    assertNull(api.getKnownTestsByModule(ENV));
  }

  @Test
  void parsesTestManagement(@TempDir Path tmp) throws IOException {
    Path file =
        renderToFile(
            tmp, "test-management-tests-response.ftl", "mgmt.json", Collections.emptyMap());

    FileBasedConfigurationApi api = new FileBasedConfigurationApi(null, null, null, null, file);
    Map<TestSetting, Map<String, Collection<TestFQN>>> result =
        api.getTestManagementTestsByModule(ENV, ENV.getSha(), ENV.getCommitMessage());

    Map<String, Collection<TestFQN>> quarantined = new HashMap<>();
    quarantined.put(
        "module-a",
        new HashSet<>(
            Arrays.asList(new TestFQN("suite-a", "test-a"), new TestFQN("suite-b", "test-c"))));
    quarantined.put("module-b", new HashSet<>(Arrays.asList(new TestFQN("suite-c", "test-e"))));
    assertEquals(quarantined, result.get(TestSetting.QUARANTINED));

    Map<String, Collection<TestFQN>> disabled = new HashMap<>();
    disabled.put("module-a", new HashSet<>(Arrays.asList(new TestFQN("suite-a", "test-b"))));
    disabled.put(
        "module-b",
        new HashSet<>(
            Arrays.asList(new TestFQN("suite-c", "test-d"), new TestFQN("suite-c", "test-f"))));
    assertEquals(disabled, result.get(TestSetting.DISABLED));

    Map<String, Collection<TestFQN>> attemptToFix = new HashMap<>();
    attemptToFix.put("module-a", new HashSet<>(Arrays.asList(new TestFQN("suite-b", "test-c"))));
    attemptToFix.put(
        "module-b",
        new HashSet<>(
            Arrays.asList(new TestFQN("suite-c", "test-d"), new TestFQN("suite-c", "test-e"))));
    assertEquals(attemptToFix, result.get(TestSetting.ATTEMPT_TO_FIX));
  }

  @Test
  void propagatesIOExceptionForMissingFile(@TempDir Path tmp) {
    Path missing = tmp.resolve("does-not-exist.json");
    FileBasedConfigurationApi api = new FileBasedConfigurationApi(missing, null, null, null, null);

    assertThrowsIO(() -> api.getSettings(ENV));
  }

  // --- helpers ---

  private static Path renderToFile(
      Path dir, String templateName, String outputName, Map<String, Object> data)
      throws IOException {
    String content = render(templateName, data);
    return writeText(dir, outputName, content);
  }

  private static Path writeText(Path dir, String name, String content) throws IOException {
    Path p = dir.resolve(name);
    Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    return p;
  }

  private static final Configuration FREEMARKER;

  static {
    FREEMARKER = new Configuration(Configuration.VERSION_2_3_30);
    FREEMARKER.setClassLoaderForTemplateLoading(
        FileBasedConfigurationApiTest.class.getClassLoader(), "");
    FREEMARKER.setDefaultEncoding("UTF-8");
    FREEMARKER.setLogTemplateExceptions(false);
    FREEMARKER.setWrapUncheckedExceptions(true);
    FREEMARKER.setFallbackOnNullLoopVariable(false);
    FREEMARKER.setNumberFormat("0.######");
  }

  private static String render(String templateName, Map<String, Object> data) throws IOException {
    Template template = FREEMARKER.getTemplate(FIXTURE_DIR + templateName);
    StringWriter out = new StringWriter();
    try {
      template.process(data, out);
    } catch (freemarker.template.TemplateException e) {
      throw new IOException("Failed to render template " + templateName, e);
    }
    return out.toString();
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
