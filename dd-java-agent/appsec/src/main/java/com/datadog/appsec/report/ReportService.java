package com.datadog.appsec.report;

import com.datadog.appsec.report.raw.events.AppSecEvent100;
import datadog.trace.api.TraceSegment;
import java.io.Closeable;
import java.util.Collection;

public interface ReportService extends Closeable {
  /**
   * Report that events has happened on this TraceSegment
   *
   * @param events the events to report
   * @param traceSegment the {@code TraceSegment} in question. Can be {@code null} if the
   *     TraceSegment is unknown.
   */
  void reportEvents(Collection<AppSecEvent100> events, TraceSegment traceSegment);

  void close();

  class NoOp implements ReportService {
    public static final ReportService INSTANCE = new NoOp();

    @Override
    public void reportEvents(Collection<AppSecEvent100> events, TraceSegment traceSegment) {}

    @Override
    public void close() {}
  }
}
