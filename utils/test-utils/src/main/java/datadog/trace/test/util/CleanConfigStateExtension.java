package datadog.trace.test.util;

import static org.junit.jupiter.api.AssertionFailureBuilder.assertionFailure;

import datadog.environment.EnvironmentVariables;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Asserts that no {@code DD_*} environment variable and no {@code dd.*} system property (minus a
 * small allowlist) is set around a test class.
 */
@SuppressForbidden
public class CleanConfigStateExtension implements BeforeAllCallback, AfterAllCallback {

  private static final List<String> ALLOWED_SYS_PROPS =
      Arrays.asList(
          "dd.appsec.enabled", "dd.iast.enabled", "dd.integration.grizzly-filterchain.enabled");

  private static final Predicate<String> DATADOG_ENV_VAR_FILTER = k -> k.startsWith("DD_");
  private static final Predicate<Object> DATADOG_SYS_PROPERTIES_FILTER =
      o -> {
        String key = (String) o;
        return key.startsWith("DD_") && !ALLOWED_SYS_PROPS.contains(key);
      };

  @Override
  public void beforeAll(ExtensionContext context) {
    assertClean("before");
  }

  @Override
  public void afterAll(ExtensionContext context) {
    assertClean("after");
  }

  private static void assertClean(String phase) {
    Map<String, String> leakedEnv =
        filterMap(EnvironmentVariables.getAll(), DATADOG_ENV_VAR_FILTER);
    Map<Object, Object> leakedSys =
        filterMap(System.getProperties(), DATADOG_SYS_PROPERTIES_FILTER);
    if (!leakedEnv.isEmpty() || !leakedSys.isEmpty()) {
      assertionFailure()
          .message("Leaked Datadog configuration detected " + phase + " test class")
          .reason(formatLeaks(leakedEnv, leakedSys))
          .buildAndThrow();
    }
  }

  private static <T> Map<T, T> filterMap(Map<T, T> map, Predicate<T> keyFilter) {
    return map.entrySet().stream()
        .filter(e -> keyFilter.test(e.getKey()))
        .collect(Collectors.toMap(Entry::getKey, Entry::getValue, (a, b) -> a, TreeMap::new));
  }

  private static String formatLeaks(Map<String, String> env, Map<Object, Object> sys) {
    StringBuilder sb = new StringBuilder();
    if (!env.isEmpty()) {
      sb.append("environment variables:");
      env.forEach((k, v) -> sb.append("\n  ").append(k).append('=').append(v));
    }
    if (!sys.isEmpty()) {
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append("system properties:");
      sys.forEach((k, v) -> sb.append("\n  ").append(k).append('=').append(v));
    }
    return sb.toString();
  }
}
