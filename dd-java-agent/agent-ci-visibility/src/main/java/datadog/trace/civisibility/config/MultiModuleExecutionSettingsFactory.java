package datadog.trace.civisibility.config;

import datadog.trace.api.Config;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Used for efficiency: caches settings for multiple modules that are executed with the same JVM.
 */
public class MultiModuleExecutionSettingsFactory implements ExecutionSettingsFactory {

  private final DDCache<JvmInfo, Map<String, ExecutionSettings>> cache;
  private final ExecutionSettingsFactoryImpl delegate;

  public MultiModuleExecutionSettingsFactory(Config config, ExecutionSettingsFactoryImpl delegate) {
    this.delegate = delegate;
    this.cache = DDCaches.newFixedSizeCache(config.getCiVisibilityExecutionSettingsCacheSize());
  }

  @Override
  public ExecutionSettings create(@Nonnull JvmInfo jvmInfo, @Nullable String moduleName) {
    Map<String, ExecutionSettings> executionSettingsByModule =
        cache.computeIfAbsent(jvmInfo, delegate::create);
    ExecutionSettings moduleSettings = executionSettingsByModule.get(moduleName);
    return moduleSettings != null
        ? moduleSettings
        : executionSettingsByModule.get(ExecutionSettingsFactoryImpl.DEFAULT_SETTINGS);
  }
}
