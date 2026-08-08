package datadog.trace.instrumentation.jmh;

import datadog.trace.api.civisibility.config.LibraryCapability;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.annotation.Nullable;
import org.openjdk.jmh.infra.BenchmarkParams;

public final class JmhUtils {

  public static final List<LibraryCapability> CAPABILITIES = Collections.emptyList();

  /**
   * Resolves the running JMH version. JMH does not publish an {@code Implementation-Version} in its
   * jar manifest, so {@code Package.getImplementationVersion()} returns {@code null}; the version
   * actually lives in the {@code jmh.properties} classpath resource (the same source JMH's own
   * {@code org.openjdk.jmh.util.Version} reads, which we cannot reference because it is absent in
   * older versions within the supported range). Falls back to the manifest, then {@code null}.
   */
  @Nullable
  public static String frameworkVersion() {
    try (InputStream is = org.openjdk.jmh.Main.class.getResourceAsStream("/jmh.properties")) {
      if (is != null) {
        Properties props = new Properties();
        props.load(is);
        String version = props.getProperty("jmh.version");
        if (version != null && !version.isEmpty()) {
          return version;
        }
      }
    } catch (Throwable ignored) {
      // fall through to the manifest lookup
    }
    try {
      return org.openjdk.jmh.Main.class.getPackage().getImplementationVersion();
    } catch (Throwable ignored) {
      return null;
    }
  }

  /**
   * Splits a JMH benchmark name into suite (class) and method parts.
   *
   * <p>JMH benchmark names have the form {@code "com.example.MyBenchmark.myMethod"}. {@code @Param}
   * values are <em>not</em> part of the name — they are exposed separately via {@link
   * BenchmarkParams#getParamsKeys()} (see {@link #testParameters(BenchmarkParams)}).
   */
  public static String[] splitBenchmarkName(String fullName) {
    int lastDot = fullName.lastIndexOf('.');
    if (lastDot < 0) {
      return new String[] {"", fullName};
    }
    return new String[] {fullName.substring(0, lastDot), fullName.substring(lastDot + 1)};
  }

  /**
   * Returns the {@code test.parameters} JSON string for a parameterized benchmark (one declaring
   * {@code @Param} fields), or {@code null} for an unparameterized one.
   */
  @Nullable
  public static String testParameters(BenchmarkParams params) {
    // getParamsKeys() is a raw Collection in older JMH versions, so iterate as Object and cast to
    // avoid an unchecked-conversion warning when compiling against the minimum supported version.
    Map<String, String> values = new LinkedHashMap<>();
    for (Object key : params.getParamsKeys()) {
      String name = (String) key;
      values.put(name, params.getParam(name));
    }
    if (values.isEmpty()) {
      return null;
    }
    return paramsToJson(values);
  }

  static String paramsToJson(Map<String, String> params) {
    StringBuilder displayName = new StringBuilder();
    boolean first = true;
    for (Map.Entry<String, String> entry : params.entrySet()) {
      if (!first) {
        displayName.append(", ");
      }
      first = false;
      displayName.append(entry.getKey()).append('=').append(entry.getValue());
    }
    return "{\"metadata\":{\"test_name\":\"" + escapeJson(displayName.toString()) + "\"}}";
  }

  /** Minimal JSON string escaping for benchmark names (no unicode escaping needed). */
  private static String escapeJson(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private JmhUtils() {}
}
