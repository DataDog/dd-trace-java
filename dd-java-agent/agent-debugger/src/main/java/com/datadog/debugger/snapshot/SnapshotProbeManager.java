package com.datadog.debugger.snapshot;

import com.datadog.debugger.probe.DebugSnapshotProbe;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.util.ClassNameFiltering;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SnapshotProbeManager {
  private Map<String, String> fingerprints = new HashMap<>();
  private final Map<String, DebugSnapshotProbe> probes = new ConcurrentHashMap<>();
  private final ClassNameFiltering classNameFiltering;

  public SnapshotProbeManager(ClassNameFiltering classNameFiltering) {
    this.classNameFiltering = classNameFiltering;
  }

  public Collection<DebugSnapshotProbe> getProbes() {
    return probes.values();
  }

  void addFingerprint(String fingerprint, String probeId) {
    fingerprints.put(fingerprint, probeId);
  }

  String getProbeId(String fingerprint) {
    return fingerprints.get(fingerprint);
  }

  public ClassNameFiltering getClassNameFiltering() {
    return classNameFiltering;
  }

  public String createProbesForException(boolean isEntrySpanOrigin, StackTraceElement element) {
    Where where =
        Where.convertLineToMethod(
            element.getClassName(),
            element.getMethodName(),
            null,
            String.valueOf(element.getLineNumber()));
    DebugSnapshotProbe probe = createMethodProbe(this, isEntrySpanOrigin, where);
    probes.putIfAbsent(probe.getId(), probe);
    return probe.getId();
  }

  private DebugSnapshotProbe createMethodProbe(
      SnapshotProbeManager probeManager, boolean isEntrySpanOrigin, Where where) {
    return new DebugSnapshotProbe(
        new ProbeId(UUID.randomUUID().toString(), 0), isEntrySpanOrigin, where, probeManager);
  }

  public boolean isAlreadyInstrumented(String fingerprint) {
    return fingerprints.containsKey(fingerprint);
  }
}
