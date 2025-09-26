package datadog.nativeloader;

import java.net.URL;

public interface LibraryResolver {
  default boolean isPreloaded(
   PlatformSpec platform,
   String libName)
  {
    return false;
  }
  
  URL resolve(
   PathLocator pathLocator,
   String component,
   PlatformSpec platformSpec,
   String libName);
}
