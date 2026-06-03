package datadog.trace.civisibility.config;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.api.civisibility.config.TestFQN;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestMetadata;
import datadog.trace.civisibility.config.api.dto.request.TracerEnvironment;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parse-and-shape contract tests that every {@link ConfigurationApi} implementation must satisfy.
 *
 * <p>Subclasses wire fixtures (HTTP server, files on disk, ...) and assert that the configured
 * implementation produces the expected domain object for each canonical response shape.
 */
abstract class AbstractConfigurationApiContractTest {

  protected static final String FIXTURE_DIR = "/datadog/trace/civisibility/config/";

  protected static final TracerEnvironment ENV = envWithBundle(null);

  protected static TracerEnvironment envWithBundle(String testBundle) {
    return TracerEnvironment.builder()
        .service("foo")
        .env("foo_env")
        .repositoryUrl("https://github.com/DataDog/foo")
        .branch("prod")
        .sha("d64185e45d1722ab3a53c45be47accae")
        .commitMessage("full commit message")
        .testBundle(testBundle)
        .build();
  }

  protected enum Endpoint {
    SETTINGS,
    SKIPPABLE_TESTS,
    FLAKY_TESTS,
    KNOWN_TESTS,
    TEST_MANAGEMENT
  }

  /**
   * Build a {@link ConfigurationApi} that responds to a call against {@code endpoint} with {@code
   * responseBody}. The other endpoints are not exercised in the contract tests and need not be
   * wired by the subclass.
   */
  protected abstract ConfigurationApi apiReturning(Endpoint endpoint, String responseBody)
      throws IOException;

  static Stream<Arguments> parsesSettingsArguments() {
    return Stream.of(
        arguments(
            "all flags off, default branch unset",
            new CiVisibilitySettings(
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                EarlyFlakeDetectionSettings.DEFAULT,
                TestManagementSettings.DEFAULT,
                null,
                false)),
        arguments(
            "all flags on, default branch set",
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
                false)),
        arguments(
            "mixed flags, single execution-by-duration",
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
                    true, singletonList(new ExecutionsByDuration(1000, 3)), 10),
                new TestManagementSettings(true, 10),
                "master",
                false)),
        arguments(
            "mixed flags, multiple executions-by-duration spanning the 60s boundary",
            new CiVisibilitySettings(
                false,
                false,
                true,
                true,
                false,
                false,
                true,
                true,
                false,
                new EarlyFlakeDetectionSettings(
                    true,
                    Arrays.asList(
                        new ExecutionsByDuration(5000, 3), new ExecutionsByDuration(120000, 2)),
                    10),
                new TestManagementSettings(true, 20),
                "prod",
                false)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("parsesSettingsArguments")
  void parsesSettings(String scenario, CiVisibilitySettings expected) throws IOException {
    Map<String, Object> data = new HashMap<>();
    data.put("settings", expected);
    String body = render("settings-response.ftl", data);

    ConfigurationApi api = apiReturning(Endpoint.SETTINGS, body);

    assertEquals(expected, api.getSettings(ENV));
  }

  static Stream<Arguments> parsesSkippableTestsArguments() {
    Map<String, Map<TestIdentifier, TestMetadata>> twoModules = new HashMap<>();
    Map<TestIdentifier, TestMetadata> bundleA = new HashMap<>();
    bundleA.put(new TestIdentifier("suite-a", "name-a", "parameters-a"), new TestMetadata(true));
    twoModules.put("testBundle-a", bundleA);
    Map<TestIdentifier, TestMetadata> bundleB = new HashMap<>();
    bundleB.put(new TestIdentifier("suite-b", "name-b", null), new TestMetadata(false));
    twoModules.put("testBundle-b", bundleB);

    // Tests in the "one module" fixture omit test.bundle from configurations; the parser falls
    // back to the tracer environment's testBundle to determine module assignment.
    Map<String, Map<TestIdentifier, TestMetadata>> oneModule = new HashMap<>();
    Map<TestIdentifier, TestMetadata> singleBundle = new HashMap<>();
    singleBundle.put(
        new TestIdentifier("suite-a", "name-a", "parameters-a"), new TestMetadata(true));
    singleBundle.put(new TestIdentifier("suite-b", "name-b", null), new TestMetadata(true));
    oneModule.put("testBundle-a", singleBundle);

    return Stream.of(
        arguments("two modules", ENV, "skippable-response.ftl", twoModules),
        arguments(
            "one module",
            envWithBundle("testBundle-a"),
            "skippable-response-one-module.ftl",
            oneModule));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("parsesSkippableTestsArguments")
  void parsesSkippableTests(
      String scenario,
      TracerEnvironment env,
      String fixture,
      Map<String, Map<TestIdentifier, TestMetadata>> expectedTests)
      throws IOException {
    String body = render(fixture, Collections.emptyMap());

    ConfigurationApi api = apiReturning(Endpoint.SKIPPABLE_TESTS, body);
    SkippableTests result = api.getSkippableTests(env);

    assertEquals(expectedTests, result.getIdentifiersByModule());
    assertEquals("11223344", result.getCorrelationId());
    Map<String, BitSet> coverage = result.getCoveredLinesByRelativeSourcePath();
    assertEquals(3, coverage.size());
    assertEquals(
        bits(0, 1, 2, 3, 4, 5, 6, 7, 14, 15, 24, 25, 26, 27, 28, 29, 30, 31),
        coverage.get("src/main/java/Calculator.java"));
    assertEquals(
        bits(24, 25, 26, 27, 28, 29, 30, 37, 38, 39, 50, 51, 52, 53, 54, 55),
        coverage.get("src/main/java/utils/Math.java"));
    assertEquals(
        bits(0, 1, 2, 3, 4, 5, 6, 7, 12, 13, 14, 15, 27, 28, 29, 30, 50, 51, 52, 53, 54, 55),
        coverage.get("src/test/java/CalculatorTest.java"));
  }

  private static BitSet bits(int... positions) {
    BitSet b = new BitSet();
    for (int p : positions) {
      b.set(p);
    }
    return b;
  }

  static Stream<Arguments> parsesFlakyTestsArguments() {
    Map<String, Collection<TestFQN>> twoModules = new HashMap<>();
    twoModules.put("testBundle-a", new HashSet<>(singletonList(new TestFQN("suite-a", "name-a"))));
    twoModules.put("testBundle-b", new HashSet<>(singletonList(new TestFQN("suite-b", "name-b"))));

    Map<String, Collection<TestFQN>> oneModule = new HashMap<>();
    oneModule.put("testBundle-a", new HashSet<>(singletonList(new TestFQN("suite-a", "name-a"))));

    return Stream.of(
        arguments("two modules", ENV, "flaky-response.ftl", twoModules),
        arguments(
            "one module",
            envWithBundle("testBundle-a"),
            "flaky-response-one-module.ftl",
            oneModule));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("parsesFlakyTestsArguments")
  void parsesFlakyTests(
      String scenario,
      TracerEnvironment env,
      String fixture,
      Map<String, Collection<TestFQN>> expectedTests)
      throws IOException {
    String body = render(fixture, Collections.emptyMap());

    ConfigurationApi api = apiReturning(Endpoint.FLAKY_TESTS, body);
    Map<String, Collection<TestFQN>> result = api.getFlakyTestsByModule(env);

    assertEquals(expectedTests, result);
  }

  @Test
  void parsesKnownTests() throws IOException {
    String body = render("known-tests-response.ftl", Collections.emptyMap());

    ConfigurationApi api = apiReturning(Endpoint.KNOWN_TESTS, body);
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
  void parsesTestManagement() throws IOException {
    String body = render("test-management-tests-response.ftl", Collections.emptyMap());

    ConfigurationApi api = apiReturning(Endpoint.TEST_MANAGEMENT, body);
    Map<TestSetting, Map<String, Collection<TestFQN>>> result =
        api.getTestManagementTestsByModule(ENV, ENV.getSha(), ENV.getCommitMessage());

    Map<String, Collection<TestFQN>> quarantined = new HashMap<>();
    quarantined.put(
        "module-a",
        new HashSet<>(
            Arrays.asList(new TestFQN("suite-a", "test-a"), new TestFQN("suite-b", "test-c"))));
    quarantined.put("module-b", new HashSet<>(singletonList(new TestFQN("suite-c", "test-e"))));
    assertEquals(quarantined, result.get(TestSetting.QUARANTINED));

    Map<String, Collection<TestFQN>> disabled = new HashMap<>();
    disabled.put("module-a", new HashSet<>(singletonList(new TestFQN("suite-a", "test-b"))));
    disabled.put(
        "module-b",
        new HashSet<>(
            Arrays.asList(new TestFQN("suite-c", "test-d"), new TestFQN("suite-c", "test-f"))));
    assertEquals(disabled, result.get(TestSetting.DISABLED));

    Map<String, Collection<TestFQN>> attemptToFix = new HashMap<>();
    attemptToFix.put("module-a", new HashSet<>(singletonList(new TestFQN("suite-b", "test-c"))));
    attemptToFix.put(
        "module-b",
        new HashSet<>(
            Arrays.asList(new TestFQN("suite-c", "test-d"), new TestFQN("suite-c", "test-e"))));
    assertEquals(attemptToFix, result.get(TestSetting.ATTEMPT_TO_FIX));
  }

  protected static String render(String templateName, Map<String, Object> data) throws IOException {
    Template template = FREEMARKER.getTemplate(FIXTURE_DIR + templateName);
    StringWriter out = new StringWriter();
    try {
      template.process(data, out);
    } catch (TemplateException e) {
      throw new IOException("Failed to render template " + templateName, e);
    }
    return out.toString();
  }

  private static final Configuration FREEMARKER;

  static {
    FREEMARKER = new Configuration(Configuration.VERSION_2_3_30);
    FREEMARKER.setClassLoaderForTemplateLoading(
        AbstractConfigurationApiContractTest.class.getClassLoader(), "");
    FREEMARKER.setDefaultEncoding("UTF-8");
    FREEMARKER.setLogTemplateExceptions(false);
    FREEMARKER.setWrapUncheckedExceptions(true);
    FREEMARKER.setFallbackOnNullLoopVariable(false);
    FREEMARKER.setNumberFormat("0.######");
  }
}
