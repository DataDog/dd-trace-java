package datadog.trace.civisibility.config;

import datadog.trace.api.Config;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.nio.file.Path;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class CachingJvmInfoFactory implements JvmInfoFactory {

  private final DDCache<Path, JvmInfo> cache;
  private final JvmInfoFactoryImpl delegate;

  public CachingJvmInfoFactory(Config config, JvmInfoFactoryImpl delegate) {
    this.delegate = delegate;
    this.cache = DDCaches.newFixedSizeCache(config.getCiVisibilityExecutionSettingsCacheSize());
  }

  @Nonnull
  @Override
  public JvmInfo getJvmInfo(@Nullable Path jvmExecutablePath) {
    // DDCache does not support null keys.
    if (jvmExecutablePath == null) {
      // If we cannot determine forked JVM, we assume it is the same as current one.
      return JvmInfo.CURRENT_JVM;
    }
    return cache.computeIfAbsent(jvmExecutablePath, delegate::getJvmInfo);
  }
}
