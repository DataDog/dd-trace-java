package datadog.trace.civisibility.coverage;

import datadog.trace.api.civisibility.coverage.CoverageProbes;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.coverage.TestReport;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/** A store that keeps track of coverage probes allocated for multiple threads. */
public abstract class ConcurrentCoverageStore<T extends CoverageProbes> implements CoverageStore {

  private final Supplier<T> probesFactory;
  private final Map<Thread, T> probes;

  private volatile TestReport report;

  protected ConcurrentCoverageStore(Supplier<T> probesFactory) {
    this.probesFactory = probesFactory;
    this.probes = new ConcurrentHashMap<>();
  }

  @Override
  public CoverageProbes getProbes() {
    return probes.computeIfAbsent(Thread.currentThread(), this::create);
  }

  private T create(Thread thread) {
    return probesFactory.get();
  }

  @Override
  public boolean report(Long testSessionId, Long testSuiteId, long testSpanId) {
    report = report(testSessionId, testSuiteId, testSpanId, probes.values());
    return report != null && report.isNotEmpty();
  }

  @Nullable
  protected abstract TestReport report(
      Long testSessionId, Long testSuiteId, long testSpanId, Collection<T> probes);

  @Nullable
  @Override
  public TestReport getReport() {
    return report;
  }
}
