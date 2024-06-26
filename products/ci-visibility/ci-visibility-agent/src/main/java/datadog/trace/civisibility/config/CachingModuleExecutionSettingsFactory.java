package datadog.trace.civisibility.config;

import datadog.trace.api.Config;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import java.util.Objects;
import javax.annotation.Nullable;

public class CachingModuleExecutionSettingsFactory implements ModuleExecutionSettingsFactory {

  private final DDCache<Key, ModuleExecutionSettings> cache;
  private final ModuleExecutionSettingsFactory delegate;

  public CachingModuleExecutionSettingsFactory(
      Config config, ModuleExecutionSettingsFactory delegate) {
    this.delegate = delegate;
    this.cache =
        DDCaches.newFixedSizeCache(config.getCiVisibilityModuleExecutionSettingsCacheSize());
  }

  @Override
  public ModuleExecutionSettings create(JvmInfo jvmInfo, @Nullable String moduleName) {
    return cache.computeIfAbsent(
        new Key(jvmInfo, moduleName), k -> delegate.create(k.jvmInfo, k.moduleName));
  }

  private static final class Key {
    private final JvmInfo jvmInfo;
    private final String moduleName;

    private Key(JvmInfo jvmInfo, String moduleName) {
      this.jvmInfo = jvmInfo;
      this.moduleName = moduleName;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Key key = (Key) o;
      return Objects.equals(jvmInfo, key.jvmInfo) && Objects.equals(moduleName, key.moduleName);
    }

    @Override
    public int hashCode() {
      return Objects.hash(jvmInfo, moduleName);
    }
  }
}
