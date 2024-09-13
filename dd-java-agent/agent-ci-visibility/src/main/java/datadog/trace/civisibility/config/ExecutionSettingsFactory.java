package datadog.trace.civisibility.config;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface ExecutionSettingsFactory {
  ExecutionSettings create(@Nonnull JvmInfo jvmInfo, @Nullable String moduleName);
}
