package datadog.trace.api.normalize;

import datadog.trace.api.Config;
import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.function.Function;

public class HttpResourceNames {
  private static final UTF8BytesString DEFAULT_RESOURCE_NAME = UTF8BytesString.create("/");

  private static final Function<Pair<CharSequence, CharSequence>, UTF8BytesString> JOINER =
      input -> {
        if (input.getLeft() == null) {
          return UTF8BytesString.create(input.getRight());
        } else if (input.getRight() == null) {
          return DEFAULT_RESOURCE_NAME;
        }
        return UTF8BytesString.create(
            input.getLeft().toString().toUpperCase() + " " + input.getRight());
      };

  private static final DDCache<Pair<CharSequence, CharSequence>, CharSequence> JOINER_CACHE =
      DDCaches.newFixedSizeCache(128);

  private static final SimpleHttpPathNormalizer simpleHttpPathNormalizer =
      new SimpleHttpPathNormalizer();

  // Not final for testing
  private static HttpResourceNames INSTANCE;

  private final AntPatternHttpPathNormalizer serverAntPatternHttpPathNormalizer;
  private final AntPatternHttpPathNormalizer clientAntPatternHttpPathNormalizer;

  private static HttpResourceNames instance() {
    if (null == INSTANCE) {
      INSTANCE = new HttpResourceNames();
    }
    return INSTANCE;
  }

  private HttpResourceNames() {
    serverAntPatternHttpPathNormalizer =
        new AntPatternHttpPathNormalizer(Config.get().getHttpServerPathResourceNameMapping());
    clientAntPatternHttpPathNormalizer =
        new AntPatternHttpPathNormalizer(Config.get().getHttpClientPathResourceNameMapping());
  }

  public static AgentSpan setForServer(
      AgentSpan span, CharSequence method, CharSequence path, boolean encoded) {
    Pair<CharSequence, Byte> result = computeForServer(method, path, encoded);
    span.setResourceName(result.getLeft(), result.getRight());

    return span;
  }

  public static Pair<CharSequence, Byte> computeForServer(
      CharSequence method, CharSequence path, boolean encoded) {
    byte priority;

    String resourcePath =
        instance().serverAntPatternHttpPathNormalizer.normalize(path.toString(), encoded);
    if (resourcePath != null) {
      priority = ResourceNamePriorities.HTTP_SERVER_CONFIG_PATTERN_MATCH;
    } else {
      resourcePath = simpleHttpPathNormalizer.normalize(path.toString(), encoded);
      priority = ResourceNamePriorities.HTTP_PATH_NORMALIZER;
    }

    return Pair.of(join(method, resourcePath), priority);
  }

  public static AgentSpan setForClient(
      AgentSpan span, CharSequence method, CharSequence path, boolean encoded) {
    byte priority;

    String resourcePath =
        instance().clientAntPatternHttpPathNormalizer.normalize(path.toString(), encoded);
    if (resourcePath != null) {
      priority = ResourceNamePriorities.HTTP_CLIENT_CONFIG_PATTERN_MATCH;
    } else {
      resourcePath = simpleHttpPathNormalizer.normalize(path.toString(), encoded);
      priority = ResourceNamePriorities.HTTP_PATH_NORMALIZER;
    }
    span.setResourceName(join(method, resourcePath), priority);

    return span;
  }

  public static CharSequence join(CharSequence method, CharSequence path) {
    return JOINER_CACHE.computeIfAbsent(Pair.of(method, path), JOINER);
  }
}
