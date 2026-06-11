package datadog.trace.api;

import static datadog.trace.util.ConfigStrings.toEnvVar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
 * Drift-guard test keeping the {@code "sensitive": true} entries in {@code
 * metadata/supported-configurations.json} in sync with {@code ConfigSetting.CONFIG_FILTER_LIST}.
 * The registry attribute is not read at runtime, so this test is what keeps the two from drifting.
 */
public class SensitiveConfigRedactionTest {

  private static final String REGISTRY_RELATIVE_PATH = "metadata/supported-configurations.json";

  // Normalizes a config name to the canonical token under which its value is COLLECTED, so the
  // registry's public names line up with the property-name forms in CONFIG_FILTER_LIST. toEnvVar
  // upper-cases and replaces "." / "-" with "_"; we strip a leading "DD_" so the dotted property
  // name and the DD_ env-var form of the same config collapse together. OTLP exporter headers set
  // via the OpenTelemetry env vars are collected under the Datadog otlp.<signal>.headers keys, so
  // the OTEL_ names map onto that collected form.
  private static String canonical(String name) {
    String env = toEnvVar(name);
    if (env.startsWith("DD_")) {
      env = env.substring("DD_".length());
    }
    if (env.equals("OTEL_EXPORTER_OTLP_HEADERS")) {
      // The generic OTEL header env var funnels into every otlp.<signal>.headers; traces stands in.
      return "OTLP_TRACES_HEADERS";
    }
    if (env.startsWith("OTEL_EXPORTER_OTLP_") && env.endsWith("_HEADERS")) {
      return "OTLP_" + env.substring("OTEL_EXPORTER_OTLP_".length());
    }
    return env;
  }

  @Test
  void sensitiveRegistryEntriesAndFilterListStayInSync() {
    Set<String> registryCanonical =
        sensitiveRegistryKeys().stream()
            .map(SensitiveConfigRedactionTest::canonical)
            .collect(toTreeSet());
    Set<String> filterCanonical =
        configFilterList().stream()
            .map(SensitiveConfigRedactionTest::canonical)
            .collect(toTreeSet());

    assertFalse(registryCanonical.isEmpty(), "expected at least one \"sensitive\": true config");
    assertEquals(
        registryCanonical,
        filterCanonical,
        "Registry \"sensitive\": true entries and ConfigSetting.CONFIG_FILTER_LIST must match after "
            + "canonicalization. Reconcile metadata/supported-configurations.json and "
            + "CONFIG_FILTER_LIST in ConfigSetting.java.");
  }

  // Registry keys for every entry marked "sensitive": true. Aliases are not collected separately --
  // a value is always collected under its primary key's property name -- so they are not needed
  // here.
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
      for (Object def : (List<Object>) entry.getValue()) {
        Map<String, Object> definition = (Map<String, Object>) def;
        if (Boolean.TRUE.equals(definition.get("sensitive"))) {
          sensitive.add(entry.getKey());
        }
      }
    }
    return sensitive;
  }

  // Reads CONFIG_FILTER_LIST from ConfigSetting via reflection.
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

  // Walks up from the working directory until metadata/supported-configurations.json is found.
  private static Path locateRegistry() {
    Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    for (Path current = dir; current != null; current = current.getParent()) {
      Path candidate = current.resolve(REGISTRY_RELATIVE_PATH);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("Could not locate " + REGISTRY_RELATIVE_PATH + " from " + dir);
  }

  private static java.util.stream.Collector<String, ?, TreeSet<String>> toTreeSet() {
    return Collectors.toCollection(TreeSet::new);
  }
}
