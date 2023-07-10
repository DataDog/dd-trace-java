package datadog.trace.api.civisibility.config;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

public class ModuleExecutionSettings {

  private final Map<String, String> systemProperties;
  private final Map<Path, List<SkippableTest>> skippableTestsByModule;

  public ModuleExecutionSettings(
      Map<String, String> systemProperties, Map<Path, List<SkippableTest>> skippableTestsByModule) {
    this.systemProperties = systemProperties;
    this.skippableTestsByModule = skippableTestsByModule;
  }

  public Map<String, String> getSystemProperties() {
    return systemProperties;
  }

  @Nullable
  public Collection<SkippableTest> getSkippableTests(Path absoluteModulePath) {
    return skippableTestsByModule.get(absoluteModulePath);
  }
}
