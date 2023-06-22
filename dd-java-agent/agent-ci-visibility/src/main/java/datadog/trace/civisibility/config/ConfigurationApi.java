package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.SkippableTest;
import java.io.IOException;
import java.util.Collection;

public interface ConfigurationApi {

  CiVisibilitySettings getSettings(TracerEnvironment tracerEnvironment) throws IOException;

  Collection<SkippableTest> getSkippableTests(TracerEnvironment tracerEnvironment)
      throws IOException;
}
