package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.TestFQN;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import javax.annotation.Nullable;

public interface ConfigurationApi {

  ConfigurationApi NO_OP =
      new ConfigurationApi() {
        @Override
        public CiVisibilitySettings getSettings(TracerEnvironment tracerEnvironment) {
          return CiVisibilitySettings.DEFAULT;
        }

        @Override
        public SkippableTests getSkippableTests(TracerEnvironment tracerEnvironment) {
          return SkippableTests.EMPTY;
        }

        @Override
        public Map<String, Collection<TestFQN>> getFlakyTestsByModule(
            TracerEnvironment tracerEnvironment) {
          return Collections.emptyMap();
        }

        @Override
        public Map<String, Collection<TestFQN>> getKnownTestsByModule(
            TracerEnvironment tracerEnvironment) {
          return Collections.emptyMap();
        }

        @Override
        public Map<TestSetting, Map<String, Collection<TestFQN>>> getTestManagementTestsByModule(
            TracerEnvironment tracerEnvironment) {
          return Collections.emptyMap();
        }

        @Override
        public ChangedFiles getChangedFiles(TracerEnvironment tracerEnvironment) {
          return ChangedFiles.EMPTY;
        }
      };

  CiVisibilitySettings getSettings(TracerEnvironment tracerEnvironment) throws IOException;

  SkippableTests getSkippableTests(TracerEnvironment tracerEnvironment) throws IOException;

  Map<String, Collection<TestFQN>> getFlakyTestsByModule(TracerEnvironment tracerEnvironment)
      throws IOException;

  @Nullable
  Map<String, Collection<TestFQN>> getKnownTestsByModule(TracerEnvironment tracerEnvironment)
      throws IOException;

  Map<TestSetting, Map<String, Collection<TestFQN>>> getTestManagementTestsByModule(
      TracerEnvironment tracerEnvironment) throws IOException;

  ChangedFiles getChangedFiles(TracerEnvironment tracerEnvironment) throws IOException;
}
