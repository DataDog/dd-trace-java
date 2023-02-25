package datadog.trace.api.normalize;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AntPatternHttpPathNormalizer extends HttpPathNormalizer {
  private static final Logger log = LoggerFactory.getLogger(AntPatternHttpPathNormalizer.class);

  /** Used to preserve original value as is when it's mapped to this value. */
  private static final String KEEP_AS_IS = "*";

  private final Map<String, String> resourceNameMatchers;
  private final AntPathMatcher matcher = new AntPathMatcher();

  private final DDCache<String, String> cache = DDCaches.newFixedSizeCache(512);
  private final Function<String, String> cacheLoader =
      new Function<String, String>() {
        @Override
        public String apply(String path) {
          for (Map.Entry<String, String> resourceNameMatcher : resourceNameMatchers.entrySet()) {
            if (matcher.match(resourceNameMatcher.getKey(), path)) {
              if (KEEP_AS_IS.equals(resourceNameMatcher.getValue())) {
                return path;
              }
              return resourceNameMatcher.getValue();
            }
          }
          return null;
        }
      };

  AntPatternHttpPathNormalizer(Map<String, String> httpResourceNameMatchers) {
    resourceNameMatchers = httpResourceNameMatchers;

    // Clean up invalid patterns
    List<String> invalidPatterns = new ArrayList<>(httpResourceNameMatchers.keySet().size());
    for (String pattern : resourceNameMatchers.keySet()) {
      if (!matcher.isPattern(pattern)) {
        invalidPatterns.add(pattern);
      }
    }
    for (String invalid : invalidPatterns) {
      log.warn("Invalid pattern {} removed from matchers", invalid);
      resourceNameMatchers.remove(invalid);
    }
  }

  @Override
  public String normalize(String path, boolean encoded) {
    if (encoded) {
      path = URIUtils.decode(path);
    }
    return cache.computeIfAbsent(path, cacheLoader);
  }
}
