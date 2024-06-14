package com.datadog.debugger.snapshot;

import com.datadog.debugger.probe.DebugSnapshotProbe;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.util.ClassNameFiltering;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SnapshotProbeManager {
  private Set<String> fingerprints = new HashSet<>();
  private final Map<String, DebugSnapshotProbe> probes = new ConcurrentHashMap<>();
  private final ClassNameFiltering classNameFiltering;

  public SnapshotProbeManager(ClassNameFiltering classNameFiltering) {
    this.classNameFiltering = classNameFiltering;
  }

  public Collection<DebugSnapshotProbe> getProbes() {
    return probes.values();
  }

  void addFingerprint(String fingerprint) {
    fingerprints.add(fingerprint);
  }

  public ClassNameFiltering getClassNameFiltering() {
    return classNameFiltering;
  }

  public boolean createProbesForException(StackTraceElement element) {
    boolean created = false;
    Where where =
        Where.convertLineToMethod(
            element.getClassName(),
            element.getMethodName(),
            null,
            String.valueOf(element.getLineNumber()));
    DebugSnapshotProbe probe = createMethodProbe(this, where);
    created = true;
    probes.putIfAbsent(probe.getId(), probe);
    return created;
  }

  private DebugSnapshotProbe createMethodProbe(SnapshotProbeManager probeManager, Where where) {
    return new DebugSnapshotProbe(
        new ProbeId(UUID.randomUUID().toString(), 0), where, probeManager);
  }

  public boolean isAlreadyInstrumented(String fingerprint) {
    return fingerprints.contains(fingerprint);
  }
}
