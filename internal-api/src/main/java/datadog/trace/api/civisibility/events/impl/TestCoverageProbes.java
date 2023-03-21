package datadog.trace.api.civisibility.events.impl;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestCoverageProbes {
  private static final Logger log = LoggerFactory.getLogger(TestCoverageProbes.class);

  public static void record(long classId, String className, int probeId) {
    AgentSpan span = activeSpan();
    if (span != null) {
      TestCoverageProbes probes =
          span.getRequestContext().getData(RequestContextSlot.CI_VISIBILITY);
      if (probes != null) {
        probes.mark(className, probeId);
      }
    }
  }

  private final Map<String, BitSet> probeActivations;

  public TestCoverageProbes() {
    probeActivations = new HashMap<>();
  }

  public void mark(String className, int probeId) {
    probeActivations.computeIfAbsent(className, (ignored) -> new BitSet()).set(probeId, true);
  }

  public void report() {
    probeActivations.forEach(
        (className, bitset) -> {
          log.debug("{} -> {}", className, bitset.toString());
        });
  }
}
