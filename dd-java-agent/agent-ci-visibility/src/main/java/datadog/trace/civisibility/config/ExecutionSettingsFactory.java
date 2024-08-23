package datadog.trace.civisibility.config;

import javax.annotation.Nullable;

public interface ExecutionSettingsFactory {
  ExecutionSettings create(JvmInfo jvmInfo, @Nullable String moduleName);
}
