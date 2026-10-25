package datadog.trace.api.normalize;

import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP path normalizer that matches incoming paths against user-configured Ant-style path patterns
 * (and exact URI paths). Supports host-prefixed patterns (e.g. {@code
 * auth.provider-a.com/oauth2/*}) to disambiguate identical paths across hosts, as well as {@code
 * {host}} placeholder substitution in target resource names.
 */
final class AntPatternHttpPathNormalizer extends HttpPathNormalizer {
  private static final Logger log = LoggerFactory.getLogger(AntPatternHttpPathNormalizer.class);

  /** Used to preserve original value as is when it's mapped to this value. */
  private static final String KEEP_AS_IS = "*";

  private static final String HOST_PLACEHOLDER = "{host}";

  private final Map<String, String> resourceNameMatchers;
  private final AntPathMatcher matcher = new AntPathMatcher();

  private final DDCache<Pair<String, String>, String> cache = DDCaches.newFixedSizeCache(512);
  private final Function<Pair<String, String>, String> cacheLoader =
      new Function<Pair<String, String>, String>() {
        @Override
        public String apply(Pair<String, String> input) {
          String host = input.getLeft();
          String path = input.getRight();
          if (path == null) {
            return null;
          }

          boolean hasHost = host != null && !host.isEmpty();
          if (hasHost) {
            String hostAndPath = path.startsWith("/") ? (host + path) : (host + "/" + path);
            for (Map.Entry<String, String> entry : resourceNameMatchers.entrySet()) {
              String pattern = entry.getKey();
              if (!pattern.startsWith("/") && matcher.match(pattern, hostAndPath)) {
                return formatResource(entry.getValue(), host, path);
              }
            }
          }

          for (Map.Entry<String, String> entry : resourceNameMatchers.entrySet()) {
            String pattern = entry.getKey();
            if (matcher.match(pattern, path)) {
              return formatResource(entry.getValue(), host, path);
            }
          }

          return null;
        }
      };

  AntPatternHttpPathNormalizer(Map<String, String> httpResourceNameMatchers) {
    resourceNameMatchers = new LinkedHashMap<>();
    if (httpResourceNameMatchers != null) {
      for (Map.Entry<String, String> entry : httpResourceNameMatchers.entrySet()) {
        String pattern = entry.getKey();
        if (pattern == null || pattern.trim().isEmpty()) {
          log.warn("Invalid empty pattern removed from matchers");
          continue;
        }
        // Normalize URL scheme if present (e.g. https://example.com/foo -> example.com/foo)
        if (pattern.startsWith("http://")) {
          pattern = pattern.substring(7);
        } else if (pattern.startsWith("https://")) {
          pattern = pattern.substring(8);
        } else if (pattern.startsWith("//")) {
          pattern = pattern.substring(2);
        }
        resourceNameMatchers.put(pattern, entry.getValue());
      }
    }
  }

  private String formatResource(String target, String host, String path) {
    if (KEEP_AS_IS.equals(target)) {
      return path;
    }
    if (target != null && target.contains(HOST_PLACEHOLDER)) {
      return target.replace(HOST_PLACEHOLDER, host != null ? host : "");
    }
    return target;
  }

  @Override
  public String normalize(String path, boolean encoded) {
    return normalize(null, path, encoded);
  }

  @Override
  public String normalize(String host, String path, boolean encoded) {
    if (path == null) {
      return null;
    }
    if (encoded) {
      path = URIUtils.decode(path);
    }
    return cache.computeIfAbsent(Pair.of(host, path), cacheLoader);
  }
}
