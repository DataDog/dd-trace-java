package datadog.trace.api;

import static datadog.trace.util.ConfigStrings.toEnvVar;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * Drift-guard test that keeps the {@code "sensitive": true} attribute in {@code
 * metadata/supported-configurations.json} consistent with the redaction actually performed by
 * {@link ConfigSetting}.
 *
 * <p>Telemetry redaction is driven by {@code ConfigSetting.CONFIG_FILTER_LIST}; the registry
 * attribute is otherwise not read at runtime. Without this guard, marking a configuration {@code
 * sensitive: true} in the registry without adding it to the filter list (or vice-versa) would
 * silently leave that configuration unredacted in configuration telemetry. This test fails CI when
 * the two drift apart.
 */
public class SensitiveConfigRedactionTest {

  private static final String REGISTRY_RELATIVE_PATH = "metadata/supported-configurations.json";

  /**
   * Normalizes any config name form -- env-var ({@code DD_API_KEY}), dotted system property ({@code
   * dd.api-key}), or bare dotted name ({@code otlp.traces.headers}) -- to a single canonical token
   * so the registry keys and the filter-list entries can be compared.
   *
   * <p>{@link datadog.trace.util.ConfigStrings#toEnvVar(String)} upper-cases and replaces {@code .}
   * / {@code -} with {@code _}, but it does not unify the {@code DD_} prefix: a registry env name
   * such as {@code DD_OTLP_TRACES_HEADERS} and the filter's dotted {@code otlp.traces.headers}
   * (which {@code toEnvVar} turns into {@code OTLP_TRACES_HEADERS}) would otherwise not match. We
   * strip a leading {@code DD_} after {@code toEnvVar} so both collapse onto {@code
   * OTLP_TRACES_HEADERS}. {@code OTEL_*} names have no {@code DD_} prefix and are unaffected.
   */
  private static String canonical(String name) {
    String env = toEnvVar(name);
    if (env.startsWith("DD_")) {
      env = env.substring("DD_".length());
    }
    return env;
  }

  @Test
  void everySensitiveConfigIsRedacted() {
    Set<String> sensitiveRegistryKeys = sensitiveRegistryKeys();
    assertTrue(
        !sensitiveRegistryKeys.isEmpty(),
        "expected at least one config marked \"sensitive\": true in " + REGISTRY_RELATIVE_PATH);

    Set<String> filterCanonical =
        configFilterList().stream()
            .map(SensitiveConfigRedactionTest::canonical)
            .collect(toTreeSet());

    Set<String> notRedacted = new TreeSet<>();
    for (String key : sensitiveRegistryKeys) {
      if (!filterCanonical.contains(canonical(key))) {
        notRedacted.add(key);
      }
    }

    if (!notRedacted.isEmpty()) {
      fail(
          "These configurations are marked \"sensitive\": true in "
              + REGISTRY_RELATIVE_PATH
              + " but are NOT redacted by ConfigSetting.CONFIG_FILTER_LIST. Add them (in env-var "
              + "and/or dotted form) to CONFIG_FILTER_LIST in ConfigSetting.java, or drop the "
              + "\"sensitive\": true marker:\n  "
              + String.join("\n  ", notRedacted));
    }
  }

  /**
   * Advisory only: surfaces filter-list entries that have no {@code "sensitive": true} counterpart
   * in the registry. This does not fail the build -- some entries (e.g. profiling api keys) are
   * legitimately redacted without being registry-sensitive -- but it makes intentional asymmetry
   * visible in the logs.
   */
  @Test
  void reportsFilterEntriesNotMarkedSensitive() {
    Set<String> sensitiveCanonical =
        sensitiveRegistryKeys().stream()
            .map(SensitiveConfigRedactionTest::canonical)
            .collect(toTreeSet());

    Set<String> filterOnly = new TreeSet<>();
    for (String entry : configFilterList()) {
      if (!sensitiveCanonical.contains(canonical(entry))) {
        filterOnly.add(entry);
      }
    }

    if (!filterOnly.isEmpty()) {
      System.out.println(
          "[advisory] CONFIG_FILTER_LIST entries with no \"sensitive\": true marker in "
              + REGISTRY_RELATIVE_PATH
              + " (not a failure): "
              + filterOnly);
    }
  }

  @SuppressWarnings("unchecked")
  private static Set<String> sensitiveRegistryKeys() {
    Path registry = locateRegistry();
    String content;
    try {
      content = new String(Files.readAllBytes(registry), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + registry, e);
    }

    Object parsed = new Load(LoadSettings.builder().build()).loadFromString(content);
    Map<String, Object> root = (Map<String, Object>) parsed;
    Map<String, Object> supported = (Map<String, Object>) root.get("supportedConfigurations");

    Set<String> sensitive = new TreeSet<>();
    for (Map.Entry<String, Object> entry : supported.entrySet()) {
      // Each value is a list of versioned definitions; the config is sensitive if any marks it so.
      for (Object def : (List<Object>) entry.getValue()) {
        Object flag = ((Map<String, Object>) def).get("sensitive");
        if (Boolean.TRUE.equals(flag)) {
          sensitive.add(entry.getKey());
          break;
        }
      }
    }
    return sensitive;
  }

  /** Reads {@code CONFIG_FILTER_LIST} from {@link ConfigSetting} via reflection. */
  @SuppressWarnings("unchecked")
  private static Set<String> configFilterList() {
    try {
      Field field = ConfigSetting.class.getDeclaredField("CONFIG_FILTER_LIST");
      field.setAccessible(true);
      return (Set<String>) field.get(null);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new IllegalStateException("Could not read ConfigSetting.CONFIG_FILTER_LIST", e);
    }
  }

  /** Walks up from the working directory until {@code metadata/supported-configurations.json}. */
  private static Path locateRegistry() {
    Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    for (Path current = dir; current != null; current = current.getParent()) {
      Path candidate = current.resolve(REGISTRY_RELATIVE_PATH);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "Could not locate "
            + REGISTRY_RELATIVE_PATH
            + " by walking up from "
            + dir
            + ". Adjust the resolution logic in "
            + SensitiveConfigRedactionTest.class.getName());
  }

  private static java.util.stream.Collector<String, ?, TreeSet<String>> toTreeSet() {
    return Collectors.toCollection(TreeSet::new);
  }
}
