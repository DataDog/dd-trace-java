package datadog.nativeloader;

import java.net.URL;

/**
 * LibraryResolver encapsulates a library resolution strategy
 * 
 * The strategy should use the provided {@link PathLocator} to locate the desired resources.
 * The LibraryResolver may try multiple locations to find the best possible library to use.
 */
public interface LibraryResolver {
  default boolean isPreloaded(PlatformSpec platform, String libName) {
    return false;
  }

  URL resolve(PathLocator pathLocator, String component, PlatformSpec platformSpec, String libName);
}
