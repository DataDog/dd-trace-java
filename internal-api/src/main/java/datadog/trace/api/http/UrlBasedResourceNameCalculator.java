package datadog.trace.api.http;

import static datadog.trace.api.Functions.PATH_BASED_RESOURCE_NAME;

import datadog.trace.api.Config;
import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Map;

public class UrlBasedResourceNameCalculator {
  private static final boolean SHOULD_SET_URL_RESOURCE_NAME =
      Config.get().isRuleEnabled("URLAsResourceNameRule");
  private static final DDCache<Pair<String, String>, UTF8BytesString> RESOURCE_NAMES =
      SHOULD_SET_URL_RESOURCE_NAME
          ? DDCaches.<Pair<String, String>, UTF8BytesString>newFixedSizeCache(512)
          : null;

  // Needs to be before static UrlBasedResourceNameCalculator instance because it is used in the
  // constructor
  public static final PathNormalizer SIMPLE_PATH_NORMALIZER = new SimplePathNormalizer();

  public static final UrlBasedResourceNameCalculator RESOURCE_NAME_CALCULATOR =
      new UrlBasedResourceNameCalculator();
  // For access in decorators that don't use the calculator directly

  private final PathNormalizer pathNormalizer;

  // Visible for testing
  UrlBasedResourceNameCalculator() {
    Map<String, String> httpResourceNameMatchers =
        Config.get().getHttpServerPathResourceNameMapping();
    if (!httpResourceNameMatchers.isEmpty()) {
      pathNormalizer =
          new AntPatternPathNormalizer(httpResourceNameMatchers, SIMPLE_PATH_NORMALIZER);
    } else {
      pathNormalizer = SIMPLE_PATH_NORMALIZER;
    }
  }

  public UTF8BytesString calculate(String method, String path, boolean encoded) {
    return RESOURCE_NAMES.computeIfAbsent(
        Pair.of(method, pathNormalizer.normalize(path, encoded)), PATH_BASED_RESOURCE_NAME);
  }
}
