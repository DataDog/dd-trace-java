package datadog.trace.civisibility.config;

import datadog.trace.api.Config;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.nio.file.Path;

public class CachingJvmInfoFactory implements JvmInfoFactory {

  private final DDCache<Path, JvmInfo> cache;
  private final JvmInfoFactoryImpl delegate;

  public CachingJvmInfoFactory(Config config, JvmInfoFactoryImpl delegate) {
    this.delegate = delegate;
    this.cache =
        DDCaches.newFixedSizeCache(config.getCiVisibilityModuleExecutionSettingsCacheSize());
  }

  @Override
  public JvmInfo getJvmInfo(Path jvmExecutablePath) {
    return cache.computeIfAbsent(jvmExecutablePath, delegate::getJvmInfo);
  }
}
