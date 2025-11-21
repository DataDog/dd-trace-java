package datadog.trace.civisibility.coverage;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.coverage.CoverageProbes;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.civisibility.source.SourceResolutionException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.annotation.Nullable;

/** A store that keeps track of coverage probes allocated for multiple threads. */
public abstract class ConcurrentCoverageStore<T extends CoverageProbes> implements CoverageStore {

  private final Thread testThread;
  private final Function<Boolean, T> probesFactory;
  private final Map<Thread, T> probes;

  private volatile TestReport report;

  protected ConcurrentCoverageStore(Function<Boolean, T> probesFactory) {
    this.probesFactory = probesFactory;
    this.testThread = Thread.currentThread();
    this.probes = new ConcurrentHashMap<>();
  }

  @Override
  public CoverageProbes getProbes() {
    return probes.computeIfAbsent(Thread.currentThread(), this::create);
  }

  private T create(Thread thread) {
    return probesFactory.apply(thread == testThread);
  }

  @Override
  public boolean report(DDTraceId testSessionId, Long testSuiteId, long testSpanId) {
    try {
      report = report(testSessionId, testSuiteId, testSpanId, probes.values());
      return report != null && report.isNotEmpty();
    } catch (SourceResolutionException e) {
      return false;
    }
  }

  @Nullable
  protected abstract TestReport report(
      DDTraceId testSessionId, Long testSuiteId, long testSpanId, Collection<T> probes)
      throws SourceResolutionException;

  @Nullable
  @Override
  public TestReport getReport() {
    return report;
  }
}
