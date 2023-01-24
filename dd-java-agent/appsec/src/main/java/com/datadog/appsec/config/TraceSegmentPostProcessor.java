package com.datadog.appsec.config;

import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.report.raw.events.AppSecEvent100;
import datadog.trace.api.internal.TraceSegment;
import java.util.Collection;

public interface TraceSegmentPostProcessor {
  void processTraceSegment(
      TraceSegment segment, AppSecRequestContext ctx, Collection<AppSecEvent100> collectedEvents);
}
