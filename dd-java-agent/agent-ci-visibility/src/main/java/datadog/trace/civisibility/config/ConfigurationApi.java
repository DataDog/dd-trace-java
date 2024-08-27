package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.TestIdentifier;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

public interface ConfigurationApi {

  ConfigurationApi NO_OP =
      new ConfigurationApi() {
        @Override
        public CiVisibilitySettings getSettings(TracerEnvironment tracerEnvironment) {
          return CiVisibilitySettings.DEFAULT;
        }

        @Override
        public SkippableTests getSkippableTests(TracerEnvironment tracerEnvironment) {
          return new SkippableTests(null, Collections.emptyMap(), null);
        }

        @Override
        public Map<String, Collection<TestIdentifier>> getFlakyTestsByModule(
            TracerEnvironment tracerEnvironment) {
          return Collections.emptyMap();
        }

        @Override
        public Map<String, Collection<TestIdentifier>> getKnownTestsByModule(
            TracerEnvironment tracerEnvironment) {
          return Collections.emptyMap();
        }
      };

  CiVisibilitySettings getSettings(TracerEnvironment tracerEnvironment) throws IOException;

  SkippableTests getSkippableTests(TracerEnvironment tracerEnvironment) throws IOException;

  Map<String, Collection<TestIdentifier>> getFlakyTestsByModule(TracerEnvironment tracerEnvironment)
      throws IOException;

  Map<String, Collection<TestIdentifier>> getKnownTestsByModule(TracerEnvironment tracerEnvironment)
      throws IOException;
}
