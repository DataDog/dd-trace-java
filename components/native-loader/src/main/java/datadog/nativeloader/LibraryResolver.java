package datadog.nativeloader;

import java.net.URL;

/**
 * LibraryResolver encapsulates a library resolution strategy
 *
 * <p>The LibraryResolver should use the provided {@link PathLocator} to locate the desired
 * resources. The LibraryResolver may try multiple locations to find the best possible library to
 * use.
 */
@FunctionalInterface
public interface LibraryResolver {
  default boolean isPreloaded(PlatformSpec platform, String libName) {
    return false;
  }

  URL resolve(PathLocator pathLocator, String component, PlatformSpec platformSpec, String libName)
      throws Exception;
}
