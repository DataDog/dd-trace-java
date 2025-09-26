package datadog.nativeloader;

import java.net.URL;

/**
 * Resolves a component / path pair to a {@link URL}
 */
public interface PathLocator {
  public URL locate(String component, String path);
}
