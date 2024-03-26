package datadog.trace.civisibility.domain;

import datadog.trace.api.civisibility.coverage.CoverageProbeStore;
import datadog.trace.api.civisibility.domain.TestContext;
import java.util.concurrent.CopyOnWriteArrayList;

public class TestContextImpl implements TestContext {

  private final CoverageProbeStore probeStore;
  private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();

  public TestContextImpl(CoverageProbeStore probeStore) {
    this.probeStore = probeStore;
  }

  @Override
  public CoverageProbeStore getCoverageProbeStore() {
    return this.probeStore;
  }

  @Override
  public <T> void set(Class<T> key, T value) {
    for (Entry entry : entries) {
      if (entry.key == key) {
        entry.value = value;
        return;
      }
    }
    entries.addIfAbsent(new Entry(key, value));
  }

  @Override
  public <T> T get(Class<T> key) {
    for (Entry entry : entries) {
      if (entry.key == key) {
        return key.cast(entry.value);
      }
    }
    return null;
  }

  private static final class Entry {
    private final Class<?> key;
    private volatile Object value;

    private Entry(Class<?> key, Object value) {
      this.key = key;
      this.value = value;
    }
  }
}
