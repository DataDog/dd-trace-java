package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.TestIdentifier;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

public interface ConfigurationApi {

  ConfigurationApi NO_OP =
      new ConfigurationApi() {
        @Override
        public CiVisibilitySettings getSettings(TracerEnvironment tracerEnvironment) {
          return CiVisibilitySettings.DEFAULT;
        }

        @Override
        public Collection<TestIdentifier> getSkippableTests(TracerEnvironment tracerEnvironment) {
          return Collections.emptyList();
        }

        @Override
        public Collection<TestIdentifier> getFlakyTests(TracerEnvironment tracerEnvironment)
            throws IOException {
          return Collections.emptyList();
        }
      };

  CiVisibilitySettings getSettings(TracerEnvironment tracerEnvironment) throws IOException;

  Collection<TestIdentifier> getSkippableTests(TracerEnvironment tracerEnvironment)
      throws IOException;

  Collection<TestIdentifier> getFlakyTests(TracerEnvironment tracerEnvironment) throws IOException;
}
