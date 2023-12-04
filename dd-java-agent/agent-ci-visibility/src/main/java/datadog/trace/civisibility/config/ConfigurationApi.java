package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.SkippableTest;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

public interface ConfigurationApi {

  ConfigurationApi NO_OP =
      new ConfigurationApi() {
        @Override
        public CiVisibilitySettings getSettings(TracerEnvironment tracerEnvironment) {
          return new CiVisibilitySettings(false, false);
        }

        @Override
        public Collection<SkippableTest> getSkippableTests(TracerEnvironment tracerEnvironment) {
          return Collections.emptyList();
        }
      };

  CiVisibilitySettings getSettings(TracerEnvironment tracerEnvironment) throws IOException;

  Collection<SkippableTest> getSkippableTests(TracerEnvironment tracerEnvironment)
      throws IOException;
}
