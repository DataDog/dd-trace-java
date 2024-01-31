package com.datadog.debugger.exception;

import com.datadog.debugger.probe.ExceptionProbe;
import com.datadog.debugger.probe.Where;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Manages the probes used for instrumentation of exception stacktraces. */
public class ExceptionProbeManager {
  private final Set<String> fingerprints = ConcurrentHashMap.newKeySet();
  private final Map<String, ExceptionProbe> probes = new ConcurrentHashMap<>();

  public ExceptionProbeManager() {}

  public void instrument(String fingerprint, StackTraceElement[] stackTraceElements) {
    fingerprints.add(fingerprint);
    // TODO instrument each frame, filtering out thirdparty code
    for (StackTraceElement stackTraceElement : stackTraceElements) {
      if (stackTraceElement.isNativeMethod() || stackTraceElement.getLineNumber() < 0) {
        // Skip native methods and lines without line numbers
        // TODO log?
        continue;
      }
      ExceptionProbe probe =
          createMethodProbe(
              fingerprint,
              stackTraceElement.getClassName(),
              stackTraceElement.getMethodName(),
              stackTraceElement.getLineNumber());
      probes.put(probe.getId(), probe);
    }
  }

  private ExceptionProbe createMethodProbe(
      String fingerprint, String className, String methodName, int lineNumber) {
    String probeId = UUID.randomUUID().toString();
    return new ExceptionProbe(
        new ProbeId(probeId, 0),
        Where.from(className, methodName, null, String.valueOf(lineNumber)),
        null,
        null,
        null,
        fingerprint);
  }

  public boolean isAlreadyInstrumented(String fingerprint) {
    return fingerprints.contains(fingerprint);
  }

  public Collection<ExceptionProbe> getProbes() {
    return probes.values();
  }
}
