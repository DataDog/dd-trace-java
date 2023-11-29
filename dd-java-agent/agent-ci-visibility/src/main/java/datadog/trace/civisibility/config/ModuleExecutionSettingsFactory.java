package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import javax.annotation.Nullable;

public interface ModuleExecutionSettingsFactory {
  ModuleExecutionSettings create(JvmInfo jvmInfo, @Nullable String moduleName);
}
