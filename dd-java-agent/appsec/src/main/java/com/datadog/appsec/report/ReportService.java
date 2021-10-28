package com.datadog.appsec.report;

import com.datadog.appsec.report.raw.events.attack.Attack010;
import datadog.trace.api.TraceSegment;
import java.io.Closeable;
import java.util.Collection;

public interface ReportService extends Closeable {
  /**
   * Report that attacks has happened on this TraceSegment
   *
   * @param attacks the attacks to report
   * @param traceSegment the {@code TraceSegment} in question. Can be {@code null} if the
   *     TraceSegment is unknown.
   */
  void reportAttacks(Collection<Attack010> attacks, TraceSegment traceSegment);

  void close();

  class NoOp implements ReportService {
    public static final ReportService INSTANCE = new NoOp();

    @Override
    public void reportAttacks(Collection<Attack010> attacks, TraceSegment traceSegment) {}

    @Override
    public void close() {}
  }
}
