package datadog.trace.api.civisibility.domain;

import datadog.trace.api.civisibility.coverage.CoverageStore;

public interface TestContext {
  CoverageStore getCoverageStore();

  <T> void set(Class<T> key, T value);

  <T> T get(Class<T> key);
}
