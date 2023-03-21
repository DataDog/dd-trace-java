package datadog.trace.api.civisibility.events.impl;

import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

public class TestCoverageProbes {
  private static final Logger log = LoggerFactory.getLogger(TestCoverageProbes.class);

  public static void record(long classId, String className, int probeId) {
    AgentSpan span = activeSpan();
    if (span != null) {
      TestCoverageProbes probes = span.getRequestContext().getData(RequestContextSlot.CI_VISIBILITY);
      if (probes != null) {
        probes.mark(className, probeId);
      }
    }
  }

  private final Map<String, BitSet> probeActivations;

  TestCoverageProbes() {
    probeActivations = new HashMap<>();
  }

  void mark(String className, int probeId) {
    probeActivations.computeIfAbsent(className, (ignored) -> new BitSet()).set(probeId, true);
  }

  void report() {
    probeActivations.forEach((className, bitset) -> {
      log.debug("{} -> {}", className, bitset.toString());
    });
  }
}
