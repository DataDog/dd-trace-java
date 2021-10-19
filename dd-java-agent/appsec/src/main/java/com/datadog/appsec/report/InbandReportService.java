package com.datadog.appsec.report;

import com.datadog.appsec.report.raw.events.attack.Attack010;
import datadog.trace.api.TraceSegment;
import java.util.Collection;

public interface InbandReportService {
  void reportAttacks(Collection<Attack010> attacks, TraceSegment traceSegment);

  class NoOp implements InbandReportService {
    public static final InbandReportService INSTANCE = new NoOp();

    @Override
    public void reportAttacks(Collection<Attack010> attacks, TraceSegment traceSegment) {}
  }
}
