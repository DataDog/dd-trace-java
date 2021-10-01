package datadog.trace.bootstrap.instrumentation.decorator.http;

import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.RESOURCE_NAME_JOINER;

import datadog.trace.api.Config;
import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Map;

class UrlBasedResourceNameCalculator {
  private static final UTF8BytesString DEFAULT_RESOURCE_NAME = UTF8BytesString.create("/");

  // Needs to be before static UrlBasedResourceNameCalculator instance because it is used in the
  // constructor
  // For access in decorators that don't use the calculator directly
  static final PathNormalizer SIMPLE_PATH_NORMALIZER = new SimplePathNormalizer();

  static final UrlBasedResourceNameCalculator RESOURCE_NAME_CALCULATOR =
      new UrlBasedResourceNameCalculator();

  private final boolean shouldSetUrlResourceName =
      Config.get().isRuleEnabled("URLAsResourceNameRule");
  private final DDCache<Pair<CharSequence, CharSequence>, UTF8BytesString> resourceNames =
      shouldSetUrlResourceName
          ? DDCaches.<Pair<CharSequence, CharSequence>, UTF8BytesString>newFixedSizeCache(512)
          : null;

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
    if (!shouldSetUrlResourceName) {
      return DEFAULT_RESOURCE_NAME;
    }
    return resourceNames.computeIfAbsent(
        Pair.of((CharSequence) method, (CharSequence) pathNormalizer.normalize(path, encoded)),
        RESOURCE_NAME_JOINER);
  }
}
