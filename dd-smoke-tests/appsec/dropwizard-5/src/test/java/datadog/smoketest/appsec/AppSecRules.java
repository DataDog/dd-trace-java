package datadog.smoketest.appsec;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/** AppSec launch settings shared by the smoke test: default properties and custom rule merging. */
final class AppSecRules {
  private static final String DEFAULT_CONFIG_ENTRY = "appsec/default_config.json";

  private static final JsonAdapter<Object> JSON = new Moshi.Builder().build().adapter(Object.class);

  /** AppSec JVM properties, mirroring the Groovy {@code AbstractAppSecServerSmokeTest}. */
  static final String[] DEFAULT_APPSEC_PROPERTIES = {
    "-Ddd.appsec.enabled=true",
    "-Ddd.profiling.enabled=false",
    // TODO: Remove once this is the default value
    "-Ddd.api-security.enabled=true",
    "-Ddd.appsec.waf.timeout=300000",
    "-DPOWERWAF_EXIT_ON_LEAK=true",
    // disable AppSec rate limit
    "-Ddd.appsec.trace.rate.limit=-1",
    // disable http client sampling
    "-Ddd.api-security.downstream.request.body.analysis.sample_rate=1",
    "-Ddd.remote_config.enabled=false"
  };

  private AppSecRules() {}

  /**
   * Merges custom rules into the default ruleset shipped in the agent jar: default rules sharing an
   * id with a custom rule are dropped, then the custom rules are appended.
   *
   * @param agentJarPath The agent jar holding {@value #DEFAULT_CONFIG_ENTRY}.
   * @param customRulesResource The test class path resource holding a JSON array of rules.
   * @param output The file to write the merged ruleset to, for {@code -Ddd.appsec.rules}.
   * @return The absolute path of the written ruleset.
   */
  @SuppressWarnings("unchecked")
  static String writeMergedRules(String agentJarPath, String customRulesResource, File output) {
    try {
      Map<String, Object> config = (Map<String, Object>) parse(readDefaultConfig(agentJarPath));
      List<Map<String, Object>> customRules =
          (List<Map<String, Object>>) parse(readResource(customRulesResource));

      Set<Object> customRuleIds = new HashSet<>();
      for (Map<String, Object> rule : customRules) {
        customRuleIds.add(rule.get("id"));
      }
      List<Map<String, Object>> rules =
          new ArrayList<>((List<Map<String, Object>>) config.get("rules"));
      rules.removeIf(rule -> customRuleIds.contains(rule.get("id")));
      rules.addAll(customRules);
      config.put("rules", rules);

      output.getParentFile().mkdirs();
      Files.write(output.toPath(), JSON.toJson(config).getBytes(UTF_8));
      return output.getAbsolutePath();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write the merged AppSec rules to " + output, e);
    }
  }

  private static String readDefaultConfig(String agentJarPath) throws IOException {
    try (JarFile jar = new JarFile(agentJarPath)) {
      ZipEntry entry = jar.getEntry(DEFAULT_CONFIG_ENTRY);
      if (entry == null) {
        throw new IOException(DEFAULT_CONFIG_ENTRY + " not found in " + agentJarPath);
      }
      try (InputStream in = jar.getInputStream(entry)) {
        return readFully(in);
      }
    }
  }

  private static String readResource(String resource) throws IOException {
    try (InputStream in = AppSecRules.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IOException("Resource not found: " + resource);
      }
      return readFully(in);
    }
  }

  private static String readFully(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = in.read(buffer)) != -1) {
      out.write(buffer, 0, read);
    }
    return new String(out.toByteArray(), UTF_8);
  }

  /** Parses JSON, keeping integral numbers as longs so the ruleset is written back unchanged. */
  private static Object parse(String json) throws IOException {
    return normalizeNumbers(JSON.fromJson(json));
  }

  @SuppressWarnings("unchecked")
  private static Object normalizeNumbers(Object value) {
    if (value instanceof Map) {
      Map<String, Object> normalized = new LinkedHashMap<>();
      for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
        normalized.put(entry.getKey(), normalizeNumbers(entry.getValue()));
      }
      return normalized;
    }
    if (value instanceof List) {
      List<Object> normalized = new ArrayList<>();
      for (Object item : (List<Object>) value) {
        normalized.add(normalizeNumbers(item));
      }
      return normalized;
    }
    if (value instanceof Double) {
      double number = (Double) value;
      if (number == Math.rint(number) && !Double.isInfinite(number)) {
        return (long) number;
      }
    }
    return value;
  }
}
