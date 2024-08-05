package com.datadog.debugger.codeorigin;

import com.datadog.debugger.probe.CodeOriginProbe;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.util.ClassNameFiltering;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CodeOriginProbeManager {
  private Map<String, String> fingerprints = new HashMap<>();
  private final Map<String, CodeOriginProbe> probes = new ConcurrentHashMap<>();
  private final ClassNameFiltering classNameFiltering;

  public CodeOriginProbeManager(ClassNameFiltering classNameFiltering) {
    this.classNameFiltering = classNameFiltering;
  }

  public Collection<CodeOriginProbe> getProbes() {
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

  public String createProbeForFrame(StackTraceElement element, String signature) {
    Where where =
        Where.convertLineToMethod(
            element.getClassName(),
            element.getMethodName(),
            signature,
            String.valueOf(element.getLineNumber()));
    CodeOriginProbe probe =
        new CodeOriginProbe(new ProbeId(UUID.randomUUID().toString(), 0), signature, where, this);
    probes.putIfAbsent(probe.getId(), probe);
    return probe.getId();
  }

  public ClassNameFiltering classNameFiltering() {
    return classNameFiltering;
  }

  public boolean isAlreadyInstrumented(String fingerprint) {
    return fingerprints.containsKey(fingerprint);
  }
}
