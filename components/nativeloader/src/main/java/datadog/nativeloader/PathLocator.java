package datadog.nativeloader;

import java.net.URL;

/**
 * Resolves a component / path pair to a {@link URL} - called by a {@link LibraryResolver}
 */
public interface PathLocator {
  /**
   * URL to the requested resource -- or null if the resource could not be found
   */
  public URL locate(String component, String path);
}
