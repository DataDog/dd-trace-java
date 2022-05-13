package datadog.trace.agent.tooling.bytebuddy.matcher;

import datadog.trace.api.Config;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.function.Function;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.List;

/**
 * Custom {@link java.security.CodeSource} excludes configured by the user.
 *
 * <p>Matches any code source location that contains one of the configured strings.
 */
public class CodeSourceExcludes {
  private CodeSourceExcludes() {}

  private static final List<String> excludes = Config.get().getExcludedCodeSources();

  private static final DDCache<String, Boolean> excludedCodeSources;

  static {
    if (!excludes.isEmpty()) {
      excludedCodeSources = DDCaches.newFixedSizeCache(64);
    } else {
      excludedCodeSources = null;
    }
  }

  public static boolean isExcluded(ProtectionDomain protectionDomain) {
    if (null != excludedCodeSources && null != protectionDomain) {
      CodeSource codeSource = protectionDomain.getCodeSource();
      if (null != codeSource) {
        // avoid hashing on the URL because that can be a blocking operation
        URL location = codeSource.getLocation();
        return null != location
            && excludedCodeSources.computeIfAbsent(
                location.getPath(),
                new Function<String, Boolean>() {
                  @Override
                  public Boolean apply(String path) {
                    for (String name : excludes) {
                      if (path.contains(name)) {
                        return true;
                      }
                    }
                    return false;
                  }
                });
      }
    }
    return false;
  }
}
