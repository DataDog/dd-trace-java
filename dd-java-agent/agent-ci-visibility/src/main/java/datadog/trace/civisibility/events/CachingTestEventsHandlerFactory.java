package datadog.trace.civisibility.events;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import java.nio.file.Path;
import java.util.Objects;

public class CachingTestEventsHandlerFactory implements TestEventsHandler.Factory {

  private final TestEventsHandler.Factory delegate;
  private final DDCache<CacheKey, TestEventsHandler> testEventsHandlerCache;

  public CachingTestEventsHandlerFactory(TestEventsHandler.Factory delegate, int cacheSize) {
    this.delegate = delegate;
    testEventsHandlerCache = DDCaches.newFixedSizeCache(cacheSize);
  }

  @Override
  public TestEventsHandler create(
      String component, String testFramework, String testFrameworkVersion, Path path) {
    CacheKey key = new CacheKey(component, testFramework, testFrameworkVersion, path);
    return testEventsHandlerCache.computeIfAbsent(
        key, k -> delegate.create(k.component, k.testFramework, k.testFrameworkVersion, k.path));
  }

  private static final class CacheKey {
    private final String component;
    private final String testFramework;
    private final String testFrameworkVersion;
    private final Path path;

    private CacheKey(
        String component, String testFramework, String testFrameworkVersion, Path path) {
      this.component = component;
      this.testFramework = testFramework;
      this.testFrameworkVersion = testFrameworkVersion;
      this.path = path;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      CacheKey cacheKey = (CacheKey) o;
      return Objects.equals(component, cacheKey.component)
          && Objects.equals(testFramework, cacheKey.testFramework)
          && Objects.equals(testFrameworkVersion, cacheKey.testFrameworkVersion)
          && Objects.equals(path, cacheKey.path);
    }

    @Override
    public int hashCode() {
      return Objects.hash(component, testFramework, testFrameworkVersion, path);
    }
  }
}
