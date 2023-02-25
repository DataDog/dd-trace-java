package datadog.trace.api.normalize;

import datadog.trace.api.Config;
import datadog.trace.api.Pair;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;

public class HttpPathNormalizers {
  private static HttpPathNormalizers INSTANCE;

  public static HttpPathNormalizers get() {
    if (null == INSTANCE) {
      INSTANCE = new HttpPathNormalizers();
    }
    return INSTANCE;
  }

  private final SimpleHttpPathNormalizer simpleHttpPathNormalizer = new SimpleHttpPathNormalizer();
  private final AntPatternHttpPathNormalizer antPatternHttpPathNormalizer =
      new AntPatternHttpPathNormalizer(Config.get().getHttpServerPathResourceNameMapping());

  public static HttpPathNormalizer simple() {
    return get().simpleHttpPathNormalizer;
  }

  public static Pair<String, Byte> chainWithPriority(String path, boolean encoded) {
    byte priority;

    String resourcePath = get().antPatternHttpPathNormalizer.normalize(path, encoded);
    if (resourcePath != null) {
      priority = ResourceNamePriorities.HTTP_SERVER_CONFIG_PATTERN_MATCH;
    } else {
      resourcePath = get().simpleHttpPathNormalizer.normalize(path, encoded);
      priority = ResourceNamePriorities.HTTP_PATH_NORMALIZER;
    }
    return Pair.of(resourcePath, priority);
  }
}
