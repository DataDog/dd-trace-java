package datadog.trace.api.civisibility.domain;

import datadog.trace.api.civisibility.coverage.CoverageProbeStore;

public interface TestContext {
  CoverageProbeStore getCoverageProbeStore();

  <T> void set(Class<T> key, T value);

  <T> T get(Class<T> key);
}
