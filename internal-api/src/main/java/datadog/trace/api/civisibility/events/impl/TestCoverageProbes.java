package datadog.trace.api.civisibility.events.impl;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestCoverageProbes {
  private static final Logger log = LoggerFactory.getLogger(TestCoverageProbes.class);

  public static void record(long classId, String className, int probeId) {
    AgentSpan span = AgentTracer.activeSpan();
    if (span != null) {
      log.error("spanId={} classId={} {} : {}", span.getSpanId(), classId, className, probeId);
    }
  }
}
