package datadog.nativeloader;

import java.net.URL;

/** Resolves a component / path pair to a {@link URL} - called by a {@link LibraryResolver} */
@FunctionalInterface
public interface PathLocator {
  /**
   * URL to the requested resource -- or null if the resource could not be found
   *
   * <p>If the returned URL uses file protocol, then {@link NativeLoader} will provide direct access
   * to the file
   *
   * <p>If the returned URL uses a non-file protocol, then {@link NativeLoader} will call {@link
   * URL#openStream()} and copy the contents to a temporary file
   */
  URL locate(String component, String path) throws Exception;
}
